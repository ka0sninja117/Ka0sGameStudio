package com.ka0s.pictopals.game;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Authoritative Chicken Time Warp engine, run only on the hosting phone.
 * All methods must be called on the host's single worker thread (the Io
 * implementation provides runOnWorker for timer callbacks). Clients only ever
 * see public state plus their own hand, so the deck can't be peeked at.
 *
 * Digital adaptation of the card game by CrashStache Games, for private
 * family play. Chat names/colors stand in for the character cards, and the
 * timeline is rendered as a countdown strip.
 */
public class GameEngine {

    public interface Io {
        void broadcast(JSONObject o);
        void sendTo(String player, JSONObject o);
        void sys(String text);
        void runOnWorker(Runnable r);
    }

    // card ids
    public static final String CLUX = "clux", MOOCH = "mooch", FREEZE = "freeze",
            THIEF = "thief", REVERSE = "reverse", PEEK = "peek", STOCK = "stock",
            SWAP = "swap", BLOCK = "block", DEAD = "dead", SLIPS = "slips", POD = "pod";

    private static final String[][] DECK_SPEC = {
            {CLUX, "9"}, {MOOCH, "3"}, {FREEZE, "3"}, {THIEF, "6"}, {REVERSE, "2"},
            {PEEK, "4"}, {STOCK, "3"}, {SWAP, "7"}, {BLOCK, "3"},
            {SLIPS, "5"}, {DEAD, "8"}, {POD, "1"}
    };

    public static String cardName(String id) {
        switch (id) {
            case CLUX: return "Clux Capacitor";
            case MOOCH: return "Mooch";
            case FREEZE: return "Cryogenic Freeze";
            case THIEF: return "Super Thief";
            case REVERSE: return "Reverse";
            case PEEK: return "Peek-a-Boo";
            case STOCK: return "Stock Pile";
            case SWAP: return "Swap Hands";
            case BLOCK: return "Swap Block";
            case DEAD: return "You Dead";
            case SLIPS: return "Time Slips Away";
            case POD: return "Escape Pod";
            default: return id;
        }
    }

    private static final int MAX_SEATS = 6;
    private static final int MIN_SEATS = 2; // official game says 3-6; 2 works for a family duo
    private static final int REACT_TIMEOUT_S = 30;

    private enum Phase { IDLE, LOBBY, ACTIVE, OVER }

    private static class Seat {
        String name, color;
        final List<String> hand = new ArrayList<>();
        boolean alive = true, frozen = false, conn = true, out = false;
        int deadSlot = -1;
    }

    private final Io io;
    private final String hostName;
    private final Random rng = new Random();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();

    private Phase phase = Phase.IDLE;
    private final List<Seat> seats = new ArrayList<>();
    private final List<String> drawPile = new ArrayList<>();
    private final List<String> discardPile = new ArrayList<>();
    /** Slot values 10..1 then 0 = Escape Window; states 'd'=face-down 'u'=face-up 'x'=removed. */
    private final int[] tlVal = {10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0};
    private final char[] tlState = new char[11];

    private int curIdx = 0, dir = 1;
    private boolean pendReverse = false;
    private String winner = null;

    // pending reaction (Swap Block or death-Clux)
    private String pendKind = null, pendTarget = null, pendFrom = null;
    private int stashedDraws = 0;
    private ScheduledFuture<?> pendTimer;

    public GameEngine(String hostName, Io io) {
        this.hostName = hostName;
        this.io = io;
    }

    public void shutdown() {
        timers.shutdownNow();
    }

    public boolean isIdle() {
        return phase == Phase.IDLE;
    }

    // ---- entry point for all player actions ----

    public void handleAction(String name, String color, JSONObject o) {
        try {
            String a = o.optString("a");
            switch (a) {
                case "join": join(name, color); break;
                case "leave": leaveLobby(name); break;
                case "begin": begin(name); break;
                case "play": onPlay(name, o.optString("card"), o.optString("target"), o.optString("want")); break;
                case "pass": onPass(name); break;
                case "react": onReact(name, o.optBoolean("yes")); break;
                case "abort": abort(name); break;
                case "newgame": newGame(name, color); break;
            }
        } catch (Exception ignored) {
        }
    }

    /** Called when someone (re)joins the room. Reconnects their seat or shows spectator state. */
    public void playerJoined(String name, String color) {
        Seat s = seatOf(name);
        if (s != null) {
            s.conn = true;
            s.color = color;
            sendHand(s);
        }
        if (phase != Phase.IDLE) {
            io.sendTo(name, stateJson());
        }
    }

    public void playerLeft(String name) {
        Seat s = seatOf(name);
        if (s == null) return;
        s.conn = false;
        if (phase == Phase.LOBBY) {
            seats.remove(s);
            broadcastState();
            return;
        }
        if (phase != Phase.ACTIVE) return;
        if (pendKind != null && s.name.equals(pendTarget)) {
            resolveReact(CLUX.equals(pendKind)); // default: use the capacitor, decline the block
        } else if (pendKind == null && s == cur() && s.alive && !s.out) {
            io.sys("🐔 " + name + " is away — auto-drawing for them.");
            drawPhase(1);
        }
    }

    // ---- lobby ----

    private void join(String name, String color) {
        if (phase == Phase.ACTIVE || phase == Phase.OVER) return;
        if (phase == Phase.IDLE) {
            if (!name.equals(hostName)) return; // only the host opens a game lobby
            phase = Phase.LOBBY;
            io.sys("🐔 " + name + " is starting a game of Chicken Time Warp! Open the 🐔 tab to join.");
        }
        if (seatOf(name) == null && seats.size() < MAX_SEATS) {
            Seat s = new Seat();
            s.name = name;
            s.color = color;
            seats.add(s);
            io.sys("🐔 " + name + " joined the game (" + seats.size() + " playing).");
        }
        broadcastState();
    }

    private void leaveLobby(String name) {
        if (phase != Phase.LOBBY) return;
        Seat s = seatOf(name);
        if (s != null) {
            seats.remove(s);
            io.sys("🐔 " + name + " left the game.");
            broadcastState();
        }
    }

    private void begin(String name) {
        if (phase != Phase.LOBBY || !name.equals(hostName) || seats.size() < MIN_SEATS) return;

        // Build the deck: deal 4 to each player from a deck without You Dead /
        // Time Slips Away, then shuffle those back into the remaining pile.
        drawPile.clear();
        discardPile.clear();
        for (String[] spec : DECK_SPEC) {
            if (DEAD.equals(spec[0]) || SLIPS.equals(spec[0])) continue;
            for (int i = 0; i < Integer.parseInt(spec[1]); i++) drawPile.add(spec[0]);
        }
        Collections.shuffle(drawPile, rng);
        for (Seat s : seats) {
            s.hand.clear();
            s.alive = true;
            s.out = false;
            s.frozen = false;
            s.deadSlot = -1;
            for (int i = 0; i < 4; i++) s.hand.add(drawPile.remove(drawPile.size() - 1));
        }
        for (String[] spec : DECK_SPEC) {
            if (DEAD.equals(spec[0]) || SLIPS.equals(spec[0])) {
                for (int i = 0; i < Integer.parseInt(spec[1]); i++) drawPile.add(spec[0]);
            }
        }
        Collections.shuffle(drawPile, rng);

        java.util.Arrays.fill(tlState, 'd');
        dir = 1;
        pendReverse = false;
        winner = null;
        clearPending();
        curIdx = rng.nextInt(seats.size());
        phase = Phase.ACTIVE;
        io.sys("🐔 Chicken Time Warp begins! " + seats.size()
                + " chickens, one escape pod. Good luck.");
        sendAllHands();
        startTurn();
    }

    private void abort(String name) {
        if (!name.equals(hostName) || phase == Phase.IDLE) return;
        phase = Phase.IDLE;
        seats.clear();
        clearPending();
        io.sys("🐔 The host ended the game.");
        broadcastState();
    }

    private void newGame(String name, String color) {
        if (!name.equals(hostName) || phase != Phase.OVER) return;
        phase = Phase.IDLE;
        seats.clear();
        join(name, color);
    }

    // ---- turn machinery ----

    private void startTurn() {
        if (phase != Phase.ACTIVE) return;
        for (int guard = 0; guard <= seats.size(); guard++) {
            Seat s = cur();
            if (s.out || !s.alive) {
                advance();
                continue;
            }
            if (s.frozen) {
                s.frozen = false;
                io.sys("🧊 " + s.name + " is frozen solid and misses their turn.");
                advance();
                continue;
            }
            break;
        }
        if (countAlive() == 0) {
            gameOver(null);
            return;
        }
        // flip the next timeline card unless the Escape Window froze the timeline
        if (!ewOpen()) {
            int idx = firstDown();
            if (idx >= 0) {
                tlState[idx] = 'u';
                if (tlVal[idx] == 0) {
                    io.sys("🚨 ESCAPE WINDOW OPEN! Play the Escape Pod on your turn to WIN!");
                } else {
                    io.sys("⏱ " + cur().name + " flips the timeline: "
                            + tlVal[idx] + " minute" + (tlVal[idx] == 1 ? "" : "s") + " to window.");
                }
            }
        }
        broadcastState();
        if (!cur().conn) {
            io.sys("🐔 " + cur().name + " is away — auto-drawing for them.");
            drawPhase(1);
        }
    }

    private void onPlay(String name, String card, String target, String want) {
        if (phase != Phase.ACTIVE || pendKind != null) return;
        Seat me = cur();
        if (!me.name.equals(name) || !me.alive || !me.hand.contains(card)) return;
        Seat tgt = seatOf(target);

        switch (card) {
            case POD:
                if (!ewOpen()) return;
                me.hand.remove(card);
                discardPile.add(card);
                io.sys("🚀 " + me.name + " leaps into the ESCAPE POD!");
                gameOver(me);
                return;
            case CLUX:
                me.hand.remove(card);
                discardPile.add(card);
                io.sys("⚡ " + me.name + " fires the Clux Capacitor — time reverses 3 minutes!");
                rewind(3);
                drawPhase(1);
                return;
            case SWAP:
                if (tgt == null || tgt == me || tgt.out || !tgt.alive) return;
                if (me.hand.size() - 1 < 1) return; // must have a card left to swap
                me.hand.remove(card);
                discardPile.add(card);
                if (tgt.hand.contains(BLOCK)) {
                    io.sys("🔄 " + me.name + " tries to swap hands with " + tgt.name + "…");
                    askReact("block", tgt.name, me.name, 1);
                } else {
                    doSwap(me, tgt);
                    drawPhase(1);
                }
                return;
            case THIEF:
                if (tgt == null || tgt == me || tgt.out || want.isEmpty()) return;
                me.hand.remove(card);
                discardPile.add(card);
                if (tgt.hand.remove(want)) {
                    me.hand.add(want);
                    io.sys("🦹 " + me.name + " super-thieved a " + cardName(want) + " from " + tgt.name + "!");
                } else {
                    io.sys("🦹 " + me.name + " demanded a " + cardName(want) + " from " + tgt.name + " — they don't have one!");
                }
                sendHand(me);
                sendHand(tgt);
                drawPhase(1);
                return;
            case PEEK:
                if (tgt == null || tgt == me || tgt.out) return;
                me.hand.remove(card);
                discardPile.add(card);
                JSONObject pk = gameMsg("peek");
                try {
                    pk.put("target", tgt.name);
                    pk.put("cards", new JSONArray(tgt.hand));
                } catch (Exception ignored) {}
                io.sendTo(me.name, pk);
                io.sys("👀 " + me.name + " peeked at " + tgt.name + "'s hand.");
                drawPhase(1);
                return;
            case FREEZE:
                if (tgt == null || tgt == me || tgt.out || !tgt.alive) return;
                me.hand.remove(card);
                discardPile.add(card);
                tgt.frozen = true;
                io.sys("🧊 " + me.name + " cryogenically froze " + tgt.name + " — they miss their next turn!");
                drawPhase(1);
                return;
            case REVERSE:
                me.hand.remove(card);
                discardPile.add(card);
                pendReverse = true;
                io.sys("↩️ " + me.name + " reversed the order of play!");
                drawPhase(1);
                return;
            case STOCK:
                me.hand.remove(card);
                discardPile.add(card);
                io.sys("🃏 " + me.name + " raids the Stock Pile — drawing two cards.");
                drawPhase(2);
                return;
            case MOOCH:
                String take = topMoochable();
                if (take == null) return;
                me.hand.remove(card);
                discardPile.add(card);
                discardPile.remove(discardPile.lastIndexOf(take));
                me.hand.add(take);
                io.sys("🤲 " + me.name + " mooched the " + cardName(take) + " off the discard pile.");
                sendHand(me);
                endTurn();
                return;
        }
    }

    private void onPass(String name) {
        if (phase != Phase.ACTIVE || pendKind != null) return;
        if (!cur().name.equals(name)) return;
        drawPhase(1);
    }

    private void drawPhase(int n) {
        stashedDraws = n;
        continueDraws();
    }

    private void continueDraws() {
        Seat me = cur();
        while (stashedDraws > 0 && phase == Phase.ACTIVE) {
            stashedDraws--;
            String card = draw();
            if (card == null) {
                io.sys("🃏 The draw pile is empty!");
                break;
            }
            if (DEAD.equals(card)) {
                discardPile.add(card);
                io.sys("☠️ " + me.name + " drew YOU DEAD!");
                if (me.hand.contains(CLUX)) {
                    askReact("clux", me.name, me.name, 0);
                    return; // resumes in resolveReact
                }
                die(me);
                break;
            }
            if (SLIPS.equals(card)) {
                discardPile.add(card);
                resolveSlips(me);
                if (phase != Phase.ACTIVE) return;
                continue;
            }
            me.hand.add(card);
        }
        if (phase == Phase.ACTIVE) {
            sendHand(me);
            endTurn();
        }
    }

    private void resolveSlips(Seat drawer) {
        int idx = -1;
        for (int i = 0; i < tlVal.length - 1; i++) {
            if (tlState[i] != 'x') { idx = i; break; }
        }
        if (idx < 0) {
            // Final Countdown rule: only the Escape Window remains — null and void
            io.sys("⌛ " + drawer.name + " drew Time Slips Away — null and void, drawing again.");
            stashedDraws++;
            return;
        }
        tlState[idx] = 'x';
        io.sys("⌛ " + drawer.name + " drew Time Slips Away — minute " + tlVal[idx]
                + " is erased from the timeline!");
        for (Seat s : seats) {
            if (!s.alive && !s.out && s.deadSlot == idx) {
                s.out = true;
                s.deadSlot = -1;
                io.sys("💀 " + s.name + " was clinging to that minute — gone from the multiverse forever.");
            }
        }
        winCheck();
    }

    private void die(Seat s) {
        s.alive = false;
        int idx = lastUp();
        if (idx >= 0) {
            s.deadSlot = idx;
            io.sys("💀 " + s.name + " is dead — clinging to "
                    + (tlVal[idx] == 0 ? "the Escape Window" : "minute " + tlVal[idx])
                    + ". A time reversal could still save them!");
        } else {
            s.out = true;
            io.sys("💀 " + s.name + " fell into the vortex with nothing to cling to. Gone for good.");
        }
        winCheck();
    }

    private void rewind(int n) {
        boolean wasOpen = ewOpen();
        for (int k = 0; k < n; k++) {
            int idx = lastUp();
            if (idx < 0) break;
            tlState[idx] = 'd';
            for (Seat s : seats) {
                if (!s.alive && !s.out && s.deadSlot == idx) {
                    s.alive = true;
                    s.deadSlot = -1;
                    io.sys("✨ " + s.name + " is pulled back into existence!");
                    sendHand(s);
                }
            }
        }
        if (wasOpen && !ewOpen()) {
            io.sys("🚪 The Escape Window slams shut!");
        }
    }

    private void doSwap(Seat a, Seat b) {
        List<String> tmp = new ArrayList<>(a.hand);
        a.hand.clear();
        a.hand.addAll(b.hand);
        b.hand.clear();
        b.hand.addAll(tmp);
        io.sys("🔄 " + a.name + " swapped hands with " + b.name + "!");
        sendHand(a);
        sendHand(b);
    }

    private void endTurn() {
        if (phase != Phase.ACTIVE) return;
        if (pendReverse) {
            dir = -dir;
            pendReverse = false;
        }
        advance();
        startTurn();
    }

    private void advance() {
        int n = seats.size();
        for (int i = 0; i < n; i++) {
            curIdx = ((curIdx + dir) % n + n) % n;
            if (!seats.get(curIdx).out) return;
        }
    }

    // ---- reactions ----

    private void askReact(String kind, String target, String from, int drawsAfter) {
        pendKind = kind;
        pendTarget = target;
        pendFrom = from;
        if ("block".equals(kind)) stashedDraws = drawsAfter;
        JSONObject ask = gameMsg("ask");
        try {
            ask.put("kind", kind);
            ask.put("from", from);
        } catch (Exception ignored) {}
        io.sendTo(target, ask);
        broadcastState();
        Seat t = seatOf(target);
        if (t == null || !t.conn) {
            resolveReact(CLUX.equals(kind));
            return;
        }
        pendTimer = timers.schedule(() -> io.runOnWorker(() -> {
            if (pendKind != null) resolveReact(CLUX.equals(pendKind));
        }), REACT_TIMEOUT_S, TimeUnit.SECONDS);
    }

    private void onReact(String name, boolean yes) {
        if (pendKind == null || !name.equals(pendTarget)) return;
        resolveReact(yes);
    }

    private void resolveReact(boolean yes) {
        if (pendTimer != null) pendTimer.cancel(false);
        String kind = pendKind;
        Seat target = seatOf(pendTarget);
        Seat from = seatOf(pendFrom);
        clearPending();
        if (target == null || phase != Phase.ACTIVE) return;

        if ("block".equals(kind)) {
            if (yes && target.hand.remove(BLOCK)) {
                discardPile.add(BLOCK);
                io.sys("🛡 " + target.name + " played Swap Block — the swap fizzles!");
                sendHand(target);
            } else if (from != null) {
                doSwap(from, target);
            }
            continueDraws(); // the swapper still draws to end their turn
        } else { // clux (death save)
            if (yes && target.hand.remove(CLUX)) {
                discardPile.add(CLUX);
                io.sys("⚡ " + target.name + " burns a Clux Capacitor — time reverses and they LIVE!");
                rewind(3);
                sendHand(target);
                continueDraws();
            } else {
                die(target);
                if (phase == Phase.ACTIVE) {
                    stashedDraws = 0;
                    sendHand(target);
                    endTurn();
                }
            }
        }
    }

    private void clearPending() {
        pendKind = null;
        pendTarget = null;
        pendFrom = null;
    }

    // ---- helpers ----

    private void winCheck() {
        if (phase != Phase.ACTIVE) return;
        int alive = countAlive();
        if (alive == 0) {
            gameOver(null);
        } else if (alive == 1 && seats.size() > 1) {
            for (Seat s : seats) {
                if (s.alive && !s.out) {
                    io.sys("🏆 " + s.name + " is the last chicken standing!");
                    gameOver(s);
                    return;
                }
            }
        }
    }

    private void gameOver(Seat w) {
        phase = Phase.OVER;
        winner = w == null ? null : w.name;
        clearPending();
        if (pendTimer != null) pendTimer.cancel(false);
        if (w == null) {
            io.sys("💥 The multiverse collapses. Every chicken is lost. (Nobody wins.)");
        } else {
            io.sys("🏆 " + w.name + " WINS Chicken Time Warp!");
        }
        broadcastState();
    }

    private String draw() {
        if (drawPile.isEmpty()) {
            if (discardPile.isEmpty()) return null;
            drawPile.addAll(discardPile);
            discardPile.clear();
            Collections.shuffle(drawPile, rng);
            io.sys("🃏 The discard pile is shuffled into a new draw pile.");
        }
        return drawPile.remove(drawPile.size() - 1);
    }

    private String topMoochable() {
        for (int i = discardPile.size() - 1; i >= 0; i--) {
            String c = discardPile.get(i);
            if (!DEAD.equals(c) && !SLIPS.equals(c)) return c;
        }
        return null;
    }

    private Seat cur() {
        return seats.get(curIdx);
    }

    private Seat seatOf(String name) {
        if (name == null) return null;
        for (Seat s : seats) if (s.name.equals(name)) return s;
        return null;
    }

    private String colorOf(String name) {
        Seat s = seatOf(name);
        return s == null ? "#444444" : s.color;
    }

    private int countAlive() {
        int n = 0;
        for (Seat s : seats) if (s.alive && !s.out) n++;
        return n;
    }

    private boolean ewOpen() {
        return tlState[tlState.length - 1] == 'u';
    }

    private int firstDown() {
        for (int i = 0; i < tlState.length; i++) {
            if (tlState[i] == 'd') return i;
        }
        return -1;
    }

    private int lastUp() {
        for (int i = tlState.length - 1; i >= 0; i--) {
            if (tlState[i] == 'u') return i;
        }
        return -1;
    }

    // ---- state / messaging ----

    private JSONObject gameMsg(String event) {
        JSONObject o = new JSONObject();
        try {
            o.put("t", "game");
            o.put("e", event);
        } catch (Exception ignored) {}
        return o;
    }

    private void sendHand(Seat s) {
        JSONObject o = gameMsg("hand");
        try {
            o.put("cards", new JSONArray(s.hand));
        } catch (Exception ignored) {}
        io.sendTo(s.name, o);
    }

    private void sendAllHands() {
        for (Seat s : seats) sendHand(s);
    }

    private void broadcastState() {
        io.broadcast(stateJson());
    }

    private JSONObject stateJson() {
        JSONObject o = gameMsg("state");
        try {
            o.put("phase", phase.name().toLowerCase());
            o.put("host", hostName);
            o.put("dir", dir);
            o.put("ew", ewOpen());
            o.put("winner", winner);
            if (phase == Phase.ACTIVE) o.put("cur", cur().name);
            if (pendKind != null) o.put("waiting", pendTarget);
            JSONArray tl = new JSONArray();
            for (int i = 0; i < tlVal.length; i++) {
                JSONObject slot = new JSONObject();
                slot.put("v", tlVal[i]);
                slot.put("s", String.valueOf(tlState[i]));
                tl.put(slot);
            }
            o.put("timeline", tl);
            o.put("draw", drawPile.size());
            String top = topMoochable();
            o.put("discard", top == null ? JSONObject.NULL : top);
            JSONArray ps = new JSONArray();
            for (Seat s : seats) {
                JSONObject p = new JSONObject();
                p.put("n", s.name);
                p.put("c", s.color);
                p.put("alive", s.alive);
                p.put("out", s.out);
                p.put("frozen", s.frozen);
                p.put("conn", s.conn);
                p.put("hand", s.hand.size());
                p.put("dead", s.deadSlot);
                ps.put(p);
            }
            o.put("players", ps);
        } catch (Exception ignored) {}
        return o;
    }
}
