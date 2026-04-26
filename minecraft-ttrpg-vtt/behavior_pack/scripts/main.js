import { world, system } from "@minecraft/server";

// ── Dice ──────────────────────────────────────────────────────────────────────

function parseDiceNotation(input) {
  let notation = input.toLowerCase().trim();
  let advantageMode;

  if (/\b(adv|advantage)\b/.test(notation)) {
    advantageMode = 'advantage';
    notation = notation.replace(/\s*(adv|advantage)\s*$/, '').trim();
  } else if (/\b(dis|disadvantage)\b/.test(notation)) {
    advantageMode = 'disadvantage';
    notation = notation.replace(/\s*(dis|disadvantage)\s*$/, '').trim();
  }

  const match = notation.match(/^(\d*)d(\d+)([+-]\d+)?$/);
  if (!match) return null;

  const count = parseInt(match[1] || '1');
  const sides = parseInt(match[2]);
  const modifier = parseInt(match[3] || '0');

  if (count < 1 || count > 100 || sides < 2 || sides > 1000) return null;

  const rolls = [];

  if (advantageMode && count === 1) {
    const r1 = Math.floor(Math.random() * sides) + 1;
    const r2 = Math.floor(Math.random() * sides) + 1;
    rolls.push(r1, r2);
    const kept = advantageMode === 'advantage' ? Math.max(r1, r2) : Math.min(r1, r2);
    return { rolls, modifier, total: kept + modifier, notation: input.trim(), advantage: advantageMode };
  }

  for (let i = 0; i < count; i++) {
    rolls.push(Math.floor(Math.random() * sides) + 1);
  }
  const sum = rolls.reduce((a, b) => a + b, 0);
  return { rolls, modifier, total: sum + modifier, notation: input.trim() };
}

function formatDiceResult(result, playerName) {
  const isAdvDis = result.advantage !== undefined;
  const rollStr = isAdvDis
    ? `[${result.rolls[0]}, ${result.rolls[1]}]`
    : result.rolls.length === 1
      ? `${result.rolls[0]}`
      : `[${result.rolls.join(', ')}]`;

  let modStr = '';
  if (result.modifier > 0) modStr = ` + ${result.modifier}`;
  else if (result.modifier < 0) modStr = ` - ${Math.abs(result.modifier)}`;

  let advStr = '';
  if (result.advantage === 'advantage') advStr = ' §d(Advantage)§r';
  else if (result.advantage === 'disadvantage') advStr = ' §5(Disadvantage)§r';

  const isNat20 = result.rolls.some(r => r === 20);
  const totalColor = isNat20 ? '§a§l' : '§e§l';

  return `§b${playerName}§r rolled §7${result.notation}§r${advStr}: ${rollStr}${modStr} = ${totalColor}${result.total}§r`;
}

// ── Initiative Tracker ────────────────────────────────────────────────────────

class InitiativeTracker {
  constructor() {
    this.entries = [];
    this.currentIndex = -1;
    this.active = false;
  }

  add(name, value, isPlayer, playerId) {
    this.entries = this.entries.filter(e => e.name.toLowerCase() !== name.toLowerCase());
    this.entries.push({ name, value, isPlayer, playerId });
    this.entries.sort((a, b) => b.value - a.value);
    if (this.currentIndex >= this.entries.length) {
      this.currentIndex = this.entries.length - 1;
    }
  }

  remove(name) {
    const idx = this.entries.findIndex(e => e.name.toLowerCase() === name.toLowerCase());
    if (idx === -1) return false;
    this.entries.splice(idx, 1);
    if (this.currentIndex >= this.entries.length) {
      this.currentIndex = Math.max(0, this.entries.length - 1);
    }
    return true;
  }

  start() {
    if (this.entries.length === 0) return null;
    this.currentIndex = 0;
    this.active = true;
    return this.entries[0];
  }

  next() {
    if (this.entries.length === 0) return null;
    this.currentIndex = (this.currentIndex + 1) % this.entries.length;
    return this.entries[this.currentIndex];
  }

  clear() {
    this.entries = [];
    this.currentIndex = -1;
    this.active = false;
  }

  formatList() {
    if (this.entries.length === 0) return '§c[Initiative] Tracker is empty.§r';
    let out = '§6§l=== Initiative Order ===§r\n';
    for (let i = 0; i < this.entries.length; i++) {
      const e = this.entries[i];
      const arrow = i === this.currentIndex ? '§a► §r' : '  ';
      const type = e.isPlayer ? '§b[P]§r' : '§c[M]§r';
      out += `${arrow}${type} §e${e.name}§r: §f${e.value}\n`;
    }
    return out.trim();
  }
}

// ── State & helpers ───────────────────────────────────────────────────────────

const initiative = new InitiativeTracker();
const GM_TAG = 'ttrpg_gm';

function isGM(player) {
  return player.hasTag(GM_TAG) || player.isOp();
}

function hasAnyGM() {
  return world.getAllPlayers().some(p => p.hasTag(GM_TAG));
}

// ── Command handlers ──────────────────────────────────────────────────────────

function handleRoll(notation, player) {
  if (!notation) {
    player.sendMessage('§cUsage: !roll <dice>  e.g. !roll 1d20+5  |  !roll 2d6  |  !roll d20 adv§r');
    return;
  }
  const result = parseDiceNotation(notation);
  if (!result) {
    player.sendMessage(`§cInvalid dice notation: "${notation}"§r`);
    return;
  }
  world.sendMessage(formatDiceResult(result, player.name));
}

function handleInit(args, player) {
  const sub = (args[0] || 'list').toLowerCase();

  switch (sub) {
    case 'roll': {
      const mod = parseInt(args[1] || '0') || 0;
      const roll = Math.floor(Math.random() * 20) + 1;
      const total = roll + mod;
      const modStr = mod > 0 ? `+${mod}` : mod < 0 ? `${mod}` : '';
      initiative.add(player.name, total, true, player.id);
      world.sendMessage(`§b${player.name}§r rolled initiative: [${roll}]${modStr} = §e§l${total}§r`);
      world.sendMessage(initiative.formatList());
      break;
    }
    case 'add': {
      if (!isGM(player)) { player.sendMessage('§cGM only.§r'); return; }
      const name = args[1];
      const val = parseInt(args[2]);
      if (!name || isNaN(val)) { player.sendMessage('§cUsage: !init add <name> <value>§r'); return; }
      initiative.add(name, val, false);
      world.sendMessage(`§6[Initiative]§r Added §e${name}§r (${val})`);
      world.sendMessage(initiative.formatList());
      break;
    }
    case 'remove': {
      if (!isGM(player)) { player.sendMessage('§cGM only.§r'); return; }
      const name = args[1];
      if (!name) { player.sendMessage('§cUsage: !init remove <name>§r'); return; }
      if (initiative.remove(name)) {
        world.sendMessage(`§6[Initiative]§r Removed §e${name}§r`);
        world.sendMessage(initiative.formatList());
      } else {
        player.sendMessage(`§cNo entry named "${name}".§r`);
      }
      break;
    }
    case 'start': {
      if (!isGM(player)) { player.sendMessage('§cGM only.§r'); return; }
      const first = initiative.start();
      if (!first) {
        player.sendMessage('§cAdd combatants first — have players use §b!init roll§c, or use §b!init add§c.§r');
        return;
      }
      world.sendMessage('§6§l=== COMBAT BEGINS ===§r');
      world.sendMessage(initiative.formatList());
      world.sendMessage(`§6[Initiative]§r §e§l${first.name}§r, you're up!`);
      break;
    }
    case 'next': {
      if (!isGM(player)) { player.sendMessage('§cGM only.§r'); return; }
      const entry = initiative.next();
      if (!entry) { player.sendMessage('§cTracker is empty.§r'); return; }
      world.sendMessage(`§6[Initiative]§r §e§l${entry.name}§r's turn! (${entry.value})`);
      break;
    }
    case 'list':
    case 'show':
      world.sendMessage(initiative.formatList());
      break;
    case 'clear': {
      if (!isGM(player)) { player.sendMessage('§cGM only.§r'); return; }
      initiative.clear();
      world.sendMessage('§6[Initiative]§r Combat ended. Tracker cleared.');
      break;
    }
    default:
      player.sendMessage('§cUsage: !init [roll|add|remove|start|next|list|clear]§r');
  }
}

function handleGM(args, player) {
  const sub = (args[0] || '').toLowerCase();

  if (!sub || sub === 'claim') {
    if (!hasAnyGM()) {
      player.addTag(GM_TAG);
      player.sendMessage('§6§lYou are now the GM!§r');
      world.sendMessage(`§6[TTRPG]§r §e${player.name}§r has claimed the GM role.`);
    } else if (isGM(player)) {
      player.sendMessage('§eYou are already the GM.§r');
    } else {
      player.sendMessage('§cA GM already exists. Ask them to run: §b!gm add §e<yourname>§r');
    }
    return;
  }

  if (!isGM(player)) { player.sendMessage('§cGM only.§r'); return; }

  if (sub === 'add') {
    const target = world.getAllPlayers().find(p => p.name.toLowerCase() === (args[1] || '').toLowerCase());
    if (!target) { player.sendMessage(`§cPlayer "${args[1]}" not found.§r`); return; }
    target.addTag(GM_TAG);
    target.sendMessage('§6§lYou have been granted GM privileges!§r');
    world.sendMessage(`§6[TTRPG]§r §e${target.name}§r is now a GM.`);
  } else if (sub === 'remove') {
    const target = world.getAllPlayers().find(p => p.name.toLowerCase() === (args[1] || '').toLowerCase());
    if (!target) { player.sendMessage(`§cPlayer "${args[1]}" not found.§r`); return; }
    target.removeTag(GM_TAG);
    target.sendMessage('§eYour GM privileges have been removed.§r');
    world.sendMessage(`§6[TTRPG]§r §e${target.name}§r is no longer a GM.`);
  } else if (sub === 'list') {
    const gms = world.getAllPlayers().filter(p => p.hasTag(GM_TAG));
    player.sendMessage(gms.length === 0
      ? '§7No GMs currently online.§r'
      : `§6GMs online:§r ${gms.map(p => p.name).join(', ')}`);
  } else {
    player.sendMessage('§cUsage: !gm [claim|add <player>|remove <player>|list]§r');
  }
}

function handleSpawn(args, player) {
  if (!isGM(player)) { player.sendMessage('§cGM only.§r'); return; }
  const rawType = args[0];
  if (!rawType) {
    player.sendMessage('§cUsage: !spawn <entity_type> [display_name]§r\n§7Example: !spawn zombie Goblin Guard§r');
    return;
  }
  const entityType = rawType.includes(':') ? rawType : `minecraft:${rawType}`;
  const displayName = args.slice(1).join(' ') || null;

  try {
    const loc = player.location;
    const dir = player.getViewDirection();
    const spawnLoc = { x: loc.x + dir.x * 3, y: loc.y, z: loc.z + dir.z * 3 };
    const entity = player.dimension.spawnEntity(entityType, spawnLoc);
    if (displayName) entity.nameTag = displayName;
    player.sendMessage(`§aSpawned §e${displayName || entityType}§a.§r`);
  } catch {
    player.sendMessage(
      `§cFailed to spawn "${entityType}". Check the entity type name.§r\n` +
      `§7Common types: zombie, skeleton, spider, creeper, enderman, villager§r`
    );
  }
}

function handleHelp(player) {
  const gm = isGM(player);
  const lines = [
    '§6§l=== TTRPG VTT Commands ===§r',
    '§b!roll §e<dice>§r — Roll dice  (1d20, 2d6+3, d20 adv, d20 dis)',
    '§b!r§r — Shorthand for !roll',
    '§b!init roll §e[mod]§r — Roll your initiative with optional modifier',
    '§b!init list§r — Show current initiative order',
    gm ? '§b!init add §e<name> <val>§r — Add NPC/monster to initiative' : null,
    gm ? '§b!init remove §e<name>§r — Remove entry from initiative' : null,
    gm ? '§b!init start§r — Start combat (announce first turn)' : null,
    gm ? "§b!init next§r — Advance to next combatant's turn" : null,
    gm ? '§b!init clear§r — End combat and clear tracker' : null,
    gm ? '§b!spawn §e<type> [name]§r — Spawn entity in front of you' : null,
    gm ? '§b!gm add/remove §e<player>§r — Grant or revoke GM role' : null,
    gm ? '§b!gm list§r — List online GMs' : null,
    !gm ? '§b!gm claim§r — Claim GM role (only works if no GM exists)' : null,
    '§b!help§r — Show this message',
  ].filter(Boolean);
  player.sendMessage(lines.join('\n'));
}

// ── Entry point ───────────────────────────────────────────────────────────────

world.beforeEvents.chatSend.subscribe(event => {
  const msg = event.message.trim();
  if (!msg.startsWith('!')) return;

  event.cancel = true;
  const player = event.sender;
  const parts = msg.slice(1).split(/\s+/);
  const cmd = parts[0].toLowerCase();
  const args = parts.slice(1);

  system.run(() => {
    switch (cmd) {
      case 'roll': case 'r': handleRoll(args.join(' '), player); break;
      case 'init':           handleInit(args, player);           break;
      case 'gm':             handleGM(args, player);             break;
      case 'spawn':          handleSpawn(args, player);          break;
      case 'help': case 'h': handleHelp(player);                 break;
      default:
        player.sendMessage(`§cUnknown command: !${cmd} — type §b!help§c for commands.§r`);
    }
  });
});
