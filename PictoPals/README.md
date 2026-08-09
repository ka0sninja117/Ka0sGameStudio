# PictoPals

A PictoChat-style offline drawing chat for Android, inspired by the Nintendo DS
Lite's PictoChat. No internet, no accounts, no servers — everything happens over
a local WiFi network or one phone's hotspot, so it works on an airplane.

**[⬇️ Download the latest APK](PictoPals.apk)** — `PictoPals.apk` is always a
copy of the newest numbered release.

Release policy: every release is kept as its own numbered APK
(`PictoPals-v1.X.apk`) alongside the others — old versions are never deleted
by the release process (the owner prunes manually). All APKs are signed with
the same key, so any of them installs; note that Android requires an
uninstall before installing an *older* version over a newer one, and phones
in the same room should all run the same version.

## Installing (sideloading)

1. On each phone, open this file in the GitHub app or browser and download
   `PictoPals-v1.8.apk` (tap **Raw** / **Download** on GitHub).
2. Open the downloaded file. Android will ask you to allow installs from that
   app (browser/Files) — allow it, then install.
3. Play Protect may warn about an unknown developer; tap **Install anyway**.
   The app asks for **no permissions** beyond network access and never touches
   the internet.

## Using it on a plane (or anywhere without WiFi)

1. **One phone hosts the network**: turn on Airplane mode, then re-enable the
   **WiFi hotspot** (Settings → Network → Hotspot & tethering). No SIM/data
   needed — the hotspot just creates a local network.
2. **Everyone else** joins that hotspot from their WiFi settings (also fine in
   airplane mode with WiFi re-enabled).
3. Everyone opens PictoPals and enters a name + color.
4. The hotspot phone taps **Host** → room A, B, C, or D.
5. Within a couple of seconds the room appears under **Nearby rooms** on the
   other phones — tap it to join.
6. Chat! Tap the room title any time to see who is in the room. The compose
   bar has three modes, switched with the bottom buttons:
   - **Aa** (default): type a message and hit SEND (or the keyboard's send key).
   - **✏️ Draw**: sketch with a finger in your chat color (✏️ thin pen,
     🖊️ thick pen, 🧽 eraser, ↩️ undo last stroke, 🗑️ clear) and hit SEND.
   - **@ (tag someone)**: tap the **@** button next to SEND to pick a person
     from the room — or **everyone** — and it drops `@Name` into your message.
     Typing `@name` by hand works too (case doesn't matter). Anyone can use
     `@everyone`.
   - **🎲 Dice**: pick a D&D die (D4–D20) and tap ROLL — the result is rolled
     and sent to the chat in one tap. Tap the same die again to stack rolls
     ("2× D20", up to 10×) — multi-rolls show every result plus the total.
     A natural 20 on a D20 gets a red 💥 CRIT! callout.

## Chicken Time Warp 🐔

A digital adaptation of the card game by CrashStache Games (for private family
play — buy the real deck, it's great). The host opens the **🐔 Game** tab and
taps **Start a game**; everyone else joins from the same tab (2–6 players,
officially 3–6). The hosting phone runs the whole game and deals secretly, so
nobody can peek at the deck — each phone sees only its own hand.

- Your chat name and color are your chicken; the timeline is the countdown
  strip at the top of the game tab (🌀 face-down, numbers face-up, ✕ erased,
  🚪 the Escape Window).
- A **banner at the top of the game tab shows what just happened** with a big
  icon, so you never have to scan the chat log. It pops when something new
  lands, and the minute cell animates when the timeline flips.
- **When it's your turn** the whole panel picks up a warm border, and the
  🐔 tab button changes to "YOUR GO!" so you notice from the text or draw
  panes too.
- Every card has its own icon and accent color: ⚡ Clux Capacitor ·
  ☠️ You Dead · 🔄 Swap Hands · 🦹 Super Thief · ⌛ Time Slips Away ·
  👀 Peek-a-Boo · 🧊 Cryogenic Freeze · ♻️ Mooch · 🛡️ Swap Block ·
  🃏 Stock Pile · 🙃 Reverse · 🚀 Escape Pod. Player states use their own
  icons so they never blur together with cards: 💀 dead but clinging,
  👻 gone for good, 🧊 frozen, ▶️ current turn, ⚠️ disconnected.
- On your turn a card is flipped automatically; tap a card tile to play it
  (with target pickers for Swap/Thief/Peek/Freeze) or tap "Just draw & end
  turn." Tapping a card when it isn't your turn explains what it does.
- You Dead, Clux Capacitor saves, Swap Block reactions, Time Slips Away,
  Mooch restrictions, the frozen timeline, and the Final Countdown rule all
  work per the rulebook. Reaction prompts auto-resolve after 30 seconds so a
  distracted kid can't stall the game.
- Dead chickens cling to the timeline and come back if time rewinds past
  them; erased minutes take their chickens with them permanently.
- The chat keeps working during a game and doubles as the game log, so
  spectators can follow along. Players who disconnect are auto-played
  (skip + draw) until they reconnect and take back their seat.

### Getting tagged

When someone tags you, your phone **vibrates (never makes a sound)** and a
notification lands in your notification bar carrying the full message text.
That notification is not tied to the room: the host can leave, the hotspot can
die, PictoPals can be closed entirely, and the tag stays in your shade until
you swipe it away (only a reboot clears it). Tagged messages are also
highlighted in amber in the chat itself.

Messages carry ids, so reconnecting after a WiFi blip replays the chat without
re-notifying you for tags you already saw; tags that arrived while you were
away do notify when you rejoin, collapsed into one entry if there are several.

Anyone who joins a room receives its full chat history, kept by the hosting
phone (up to the last 200 messages). History lasts as long as the host stays
in the room; when the host leaves, the room closes and history resets.

At home, everyone being on the same house WiFi works too — no hotspot needed.

If room discovery ever fails (some networks block broadcasts), use
**Join by IP address…** with the host's IP (hotspot hosts are usually
`192.168.x.1`, shown in hotspot settings).

## Tech notes

- Plain Java Android app, zero external dependencies, minSdk 26 (Android 8+).
- Host phone runs a TCP relay server (port 41117) and broadcasts a UDP
  discovery beacon (port 41118) once per second; clients auto-discover rooms.
- Messages are typed text, dice rolls, or the drawing panel encoded as PNG,
  relayed to everyone in the room. Nothing is stored or sent anywhere else.
- **Background/lock survival**: while you're in a room a foreground service
  (visible as a "PictoPals — chat stays connected" notification) keeps the
  connection alive when you switch apps or your screen locks, holding CPU and
  WiFi locks so Doze/app-sleep can't cut the room. Host phones also keep
  their screen awake, since many phones drop their hotspot when the screen
  has been off a while (also check the hotspot's "turn off automatically"
  setting). Android 13+ asks for notification permission once — declining
  keeps everything working, you just won't see the notification.
- **Reliability**: heartbeat pings run both ways and silent connections are
  dropped after 30s; if a phone loses the room (WiFi blip, walked out of
  range) it auto-reconnects every 3s and re-syncs the chat from the host's
  history. When the host leaves on purpose, a goodbye message tells everyone
  the room is closed so they don't retry.
- **Hardening**: everything received from the network is validated — protocol
  lines, names, text, and drawings are size-capped, dice rolls must be a real
  D4–D20 result, image dimensions are checked before decoding, rooms cap at
  16 people, and both history and the on-screen list are bounded so memory
  stays flat. All versions of the app must match across phones.
- Built with `gradle assembleRelease` (Android SDK 35). Signed with the
  committed `keystore/pictopals.jks` (passwords: `pictopals`) — this is a
  throwaway hobby key kept in the repo so future builds can update-install
  over old ones; don't reuse it for anything that matters.

## Why not real PictoChat with a DS Lite?

Real DS PictoChat uses Nintendo's proprietary "NiFi" wireless protocol —
near-802.11b frames that phone WiFi chips cannot send or receive. No phone app
can join an actual DS PictoChat room; this app recreates the experience
phone-to-phone instead.
