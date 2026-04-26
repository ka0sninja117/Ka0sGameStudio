import { world, system, ItemStack } from "@minecraft/server";
import { ActionFormData, ModalFormData } from "@minecraft/server-ui";

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
  for (let i = 0; i < count; i++) rolls.push(Math.floor(Math.random() * sides) + 1);
  return { rolls, modifier, total: rolls.reduce((a, b) => a + b, 0) + modifier, notation: input.trim() };
}

function formatDiceResult(result, playerName) {
  const rollStr = result.advantage !== undefined
    ? `[${result.rolls[0]}, ${result.rolls[1]}]`
    : result.rolls.length === 1 ? `${result.rolls[0]}` : `[${result.rolls.join(', ')}]`;

  let modStr = '';
  if (result.modifier > 0) modStr = ` + ${result.modifier}`;
  else if (result.modifier < 0) modStr = ` - ${Math.abs(result.modifier)}`;

  const advStr = result.advantage === 'advantage' ? ' §d(Advantage)§r'
    : result.advantage === 'disadvantage' ? ' §5(Disadvantage)§r' : '';

  const nat20 = result.rolls.some(r => r === 20);
  return `§b${playerName}§r rolled §7${result.notation}§r${advStr}: ${rollStr}${modStr} = ${nat20 ? '§a§l' : '§e§l'}${result.total}§r`;
}

// ── Initiative Tracker ────────────────────────────────────────────────────────

class InitiativeTracker {
  constructor() { this.entries = []; this.currentIndex = -1; this.active = false; }

  add(name, value, isPlayer, playerId) {
    this.entries = this.entries.filter(e => e.name.toLowerCase() !== name.toLowerCase());
    this.entries.push({ name, value, isPlayer, playerId });
    this.entries.sort((a, b) => b.value - a.value);
    if (this.currentIndex >= this.entries.length) this.currentIndex = this.entries.length - 1;
  }

  remove(name) {
    const idx = this.entries.findIndex(e => e.name.toLowerCase() === name.toLowerCase());
    if (idx === -1) return false;
    this.entries.splice(idx, 1);
    if (this.currentIndex >= this.entries.length) this.currentIndex = Math.max(0, this.entries.length - 1);
    return true;
  }

  start() {
    if (!this.entries.length) return null;
    this.currentIndex = 0; this.active = true;
    return this.entries[0];
  }

  next() {
    if (!this.entries.length) return null;
    this.currentIndex = (this.currentIndex + 1) % this.entries.length;
    return this.entries[this.currentIndex];
  }

  clear() { this.entries = []; this.currentIndex = -1; this.active = false; }

  formatList() {
    if (!this.entries.length) return '§c[Initiative] Tracker is empty.§r';
    let out = '§6§l=== Initiative Order ===§r\n';
    for (let i = 0; i < this.entries.length; i++) {
      const e = this.entries[i];
      out += `${i === this.currentIndex ? '§a► §r' : '  '}${e.isPlayer ? '§b[P]§r' : '§c[M]§r'} §e${e.name}§r: §f${e.value}\n`;
    }
    return out.trim();
  }
}

// ── State & helpers ───────────────────────────────────────────────────────────

const initiative = new InitiativeTracker();
const GM_TAG = 'ttrpg_gm';

const ITEM_DICE_BAG   = '§eDice Bag§r';
const ITEM_INITIATIVE = '§bInitiative Token§r';
const ITEM_GM_WAND    = '§6GM Wand§r';

const KNOWN_ITEMS = new Set([ITEM_DICE_BAG, ITEM_INITIATIVE, ITEM_GM_WAND]);

function isGM(player) { return player.hasTag(GM_TAG) || player.isOp(); }
function hasAnyGM()    { return world.getAllPlayers().some(p => p.hasTag(GM_TAG)); }

function givePlayerKit(player) {
  const inv = player.getComponent('minecraft:inventory');
  if (!inv) return;
  const diceBag = new ItemStack('minecraft:book', 1);
  diceBag.nameTag = ITEM_DICE_BAG;
  const token = new ItemStack('minecraft:compass', 1);
  token.nameTag = ITEM_INITIATIVE;
  inv.container?.addItem(diceBag);
  inv.container?.addItem(token);
  player.sendMessage('§6[TTRPG]§r Received: §eDice Bag§r + §bInitiative Token§r');
}

function giveGMKit(player) {
  const inv = player.getComponent('minecraft:inventory');
  if (!inv) return;
  const wand = new ItemStack('minecraft:blaze_rod', 1);
  wand.nameTag = ITEM_GM_WAND;
  inv.container?.addItem(wand);
  player.sendMessage('§6[TTRPG]§r Received: §6GM Wand§r');
}

// ── Forms ─────────────────────────────────────────────────────────────────────

async function openDiceBagForm(player) {
  const TYPES = ['d4', 'd6', 'd8', 'd10', 'd12', 'd20', 'd100'];
  const SIDES = [  4,    6,    8,    10,    12,    20,    100  ];

  const res = await new ModalFormData()
    .title('Roll Dice')
    .dropdown('Dice Type', TYPES, 5)
    .slider('Number of Dice', 1, 10, 1, 1)
    .slider('Modifier', -10, 10, 1, 0)
    .toggle('Advantage  (d20 only)', false)
    .toggle('Disadvantage  (d20 only)', false)
    .show(player);

  if (res.canceled || !res.formValues) return;
  const [diceIdx, count, modifier, adv, dis] = res.formValues;
  const sides = SIDES[diceIdx];

  let notation = `${count}d${sides}`;
  if (modifier !== 0) notation += modifier > 0 ? `+${modifier}` : `${modifier}`;
  if (sides === 20 && adv && !dis) notation += ' adv';
  if (sides === 20 && dis && !adv) notation += ' dis';

  const result = parseDiceNotation(notation);
  if (result) world.sendMessage(formatDiceResult(result, player.name));
}

async function openInitiativeForm(player) {
  const res = await new ModalFormData()
    .title('Roll Initiative')
    .slider('Initiative Modifier', -10, 10, 1, 0)
    .show(player);

  if (res.canceled || !res.formValues) return;
  const [mod] = res.formValues;
  const roll = Math.floor(Math.random() * 20) + 1;
  const total = roll + mod;
  const modStr = mod > 0 ? `+${mod}` : mod < 0 ? `${mod}` : '';
  initiative.add(player.name, total, true, player.id);
  world.sendMessage(`§b${player.name}§r rolled initiative: [${roll}]${modStr} = §e§l${total}§r`);
  world.sendMessage(initiative.formatList());
}

async function openGMMenu(player) {
  if (!isGM(player)) { player.sendMessage('§cGM only.§r'); return; }

  const res = await new ActionFormData()
    .title('GM Controls')
    .button('Next Turn')
    .button('Show Initiative')
    .button('Start Combat')
    .button('Add Combatant')
    .button('Spawn Entity')
    .button('Clear Initiative')
    .button('Manage GMs')
    .show(player);

  if (res.canceled) return;

  switch (res.selection) {
    case 0: {
      const e = initiative.next();
      if (!e) { player.sendMessage('§cTracker is empty.§r'); return; }
      world.sendMessage(`§6[Initiative]§r §e§l${e.name}§r's turn! (${e.value})`);
      break;
    }
    case 1: world.sendMessage(initiative.formatList()); break;
    case 2: {
      const first = initiative.start();
      if (!first) { player.sendMessage('§cAdd combatants first.§r'); return; }
      world.sendMessage('§6§l=== COMBAT BEGINS ===§r');
      world.sendMessage(initiative.formatList());
      world.sendMessage(`§6[Initiative]§r §e§l${first.name}§r, you're up!`);
      break;
    }
    case 3: await openAddCombatantForm(player); break;
    case 4: await openSpawnForm(player); break;
    case 5: initiative.clear(); world.sendMessage('§6[Initiative]§r Combat ended. Tracker cleared.'); break;
    case 6: await openManageGMsForm(player); break;
  }
}

async function openAddCombatantForm(player) {
  const res = await new ModalFormData()
    .title('Add Combatant')
    .textField('Name', 'e.g. Goblin Archer')
    .slider('Initiative Value', 1, 30, 1, 15)
    .show(player);

  if (res.canceled || !res.formValues) return;
  const [name, value] = res.formValues;
  if (!name.trim()) { player.sendMessage('§cName cannot be empty.§r'); return; }
  initiative.add(name.trim(), value, false);
  world.sendMessage(`§6[Initiative]§r Added §e${name.trim()}§r (${value})`);
  world.sendMessage(initiative.formatList());
}

async function openSpawnForm(player) {
  const res = await new ModalFormData()
    .title('Spawn Entity')
    .textField('Entity Type', 'zombie, skeleton, spider, creeper...')
    .textField('Display Name  (optional)', 'e.g. Goblin Guard', '')
    .show(player);

  if (res.canceled || !res.formValues) return;
  const [rawType, displayName] = res.formValues;
  if (!rawType.trim()) { player.sendMessage('§cEntity type cannot be empty.§r'); return; }

  const entityType = rawType.trim().includes(':') ? rawType.trim() : `minecraft:${rawType.trim()}`;
  const label = displayName.trim() || null;
  try {
    const loc = player.location;
    const dir = player.getViewDirection();
    const entity = player.dimension.spawnEntity(entityType, { x: loc.x + dir.x * 3, y: loc.y, z: loc.z + dir.z * 3 });
    if (label) entity.nameTag = label;
    player.sendMessage(`§aSpawned §e${label || entityType}§a.§r`);
  } catch {
    player.sendMessage(`§cFailed to spawn "${entityType}". Check the entity type name.§r`);
  }
}

async function openManageGMsForm(player) {
  const others = world.getAllPlayers().filter(p => p.id !== player.id);
  if (!others.length) { player.sendMessage('§7No other players online.§r'); return; }

  const form = new ActionFormData().title('Manage GMs — tap to toggle');
  for (const p of others) form.button(`${p.hasTag(GM_TAG) ? '§a[GM] §r' : '§7[Player] §r'}${p.name}`);

  const res = await form.show(player);
  if (res.canceled) return;

  const target = others[res.selection];
  if (!target) return;
  if (target.hasTag(GM_TAG)) {
    target.removeTag(GM_TAG);
    target.sendMessage('§eYour GM privileges have been removed.§r');
    world.sendMessage(`§6[TTRPG]§r §e${target.name}§r is no longer a GM.`);
  } else {
    target.addTag(GM_TAG);
    giveGMKit(target);
    target.sendMessage('§6§lYou have been granted GM privileges!§r');
    world.sendMessage(`§6[TTRPG]§r §e${target.name}§r is now a GM.`);
  }
}

// ── Item use event ────────────────────────────────────────────────────────────

world.beforeEvents.itemUse.subscribe(event => {
  if (event.source.typeId !== 'minecraft:player') return;
  const nameTag = event.itemStack?.nameTag;
  if (!nameTag || !KNOWN_ITEMS.has(nameTag)) return;

  event.cancel = true;
  const player = event.source;

  system.run(() => {
    if (nameTag === ITEM_DICE_BAG)   openDiceBagForm(player);
    if (nameTag === ITEM_INITIATIVE) openInitiativeForm(player);
    if (nameTag === ITEM_GM_WAND)    openGMMenu(player);
  });
});

// ── Chat commands ─────────────────────────────────────────────────────────────

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
      case 'kit':            handleKit(args, player);            break;
      case 'help': case 'h': handleHelp(player);                 break;
      default: player.sendMessage(`§cUnknown command: !${cmd} — type §b!help§c for commands.§r`);
    }
  });
});

function handleRoll(notation, player) {
  if (!notation) { player.sendMessage('§cUsage: !roll <dice>  e.g. !roll 1d20+5 | !roll 2d6 | !roll d20 adv§r'); return; }
  const result = parseDiceNotation(notation);
  if (!result) { player.sendMessage(`§cInvalid dice notation: "${notation}"§r`); return; }
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
      const name = args[1]; const val = parseInt(args[2]);
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
      if (initiative.remove(name)) { world.sendMessage(`§6[Initiative]§r Removed §e${name}§r`); world.sendMessage(initiative.formatList()); }
      else player.sendMessage(`§cNo entry named "${name}".§r`);
      break;
    }
    case 'start': {
      if (!isGM(player)) { player.sendMessage('§cGM only.§r'); return; }
      const first = initiative.start();
      if (!first) { player.sendMessage('§cAdd combatants first.§r'); return; }
      world.sendMessage('§6§l=== COMBAT BEGINS ===§r');
      world.sendMessage(initiative.formatList());
      world.sendMessage(`§6[Initiative]§r §e§l${first.name}§r, you're up!`);
      break;
    }
    case 'next': {
      if (!isGM(player)) { player.sendMessage('§cGM only.§r'); return; }
      const e = initiative.next();
      if (!e) { player.sendMessage('§cTracker is empty.§r'); return; }
      world.sendMessage(`§6[Initiative]§r §e§l${e.name}§r's turn! (${e.value})`);
      break;
    }
    case 'list': case 'show': world.sendMessage(initiative.formatList()); break;
    case 'clear': {
      if (!isGM(player)) { player.sendMessage('§cGM only.§r'); return; }
      initiative.clear(); world.sendMessage('§6[Initiative]§r Combat ended. Tracker cleared.');
      break;
    }
    default: player.sendMessage('§cUsage: !init [roll|add|remove|start|next|list|clear]§r');
  }
}

function handleGM(args, player) {
  const sub = (args[0] || '').toLowerCase();
  if (!sub || sub === 'claim') {
    if (!hasAnyGM()) {
      player.addTag(GM_TAG);
      giveGMKit(player);
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
    const t = world.getAllPlayers().find(p => p.name.toLowerCase() === (args[1] || '').toLowerCase());
    if (!t) { player.sendMessage(`§cPlayer "${args[1]}" not found.§r`); return; }
    t.addTag(GM_TAG); giveGMKit(t);
    t.sendMessage('§6§lYou have been granted GM privileges!§r');
    world.sendMessage(`§6[TTRPG]§r §e${t.name}§r is now a GM.`);
  } else if (sub === 'remove') {
    const t = world.getAllPlayers().find(p => p.name.toLowerCase() === (args[1] || '').toLowerCase());
    if (!t) { player.sendMessage(`§cPlayer "${args[1]}" not found.§r`); return; }
    t.removeTag(GM_TAG);
    t.sendMessage('§eYour GM privileges have been removed.§r');
    world.sendMessage(`§6[TTRPG]§r §e${t.name}§r is no longer a GM.`);
  } else if (sub === 'list') {
    const gms = world.getAllPlayers().filter(p => p.hasTag(GM_TAG));
    player.sendMessage(gms.length ? `§6GMs online:§r ${gms.map(p => p.name).join(', ')}` : '§7No GMs online.§r');
  } else {
    player.sendMessage('§cUsage: !gm [claim|add <player>|remove <player>|list]§r');
  }
}

function handleSpawn(args, player) {
  if (!isGM(player)) { player.sendMessage('§cGM only.§r'); return; }
  const rawType = args[0];
  if (!rawType) { player.sendMessage('§cUsage: !spawn <type> [name]§r'); return; }
  const entityType = rawType.includes(':') ? rawType : `minecraft:${rawType}`;
  const label = args.slice(1).join(' ') || null;
  try {
    const loc = player.location; const dir = player.getViewDirection();
    const entity = player.dimension.spawnEntity(entityType, { x: loc.x + dir.x * 3, y: loc.y, z: loc.z + dir.z * 3 });
    if (label) entity.nameTag = label;
    player.sendMessage(`§aSpawned §e${label || entityType}§a.§r`);
  } catch { player.sendMessage(`§cFailed to spawn "${entityType}".§r`); }
}

function handleKit(args, player) {
  if (!args.length) { givePlayerKit(player); return; }
  if (!isGM(player)) { player.sendMessage('§cOnly GMs can give kits to others.§r'); return; }
  const t = world.getAllPlayers().find(p => p.name.toLowerCase() === args[0].toLowerCase());
  if (!t) { player.sendMessage(`§cPlayer "${args[0]}" not found.§r`); return; }
  givePlayerKit(t);
}

function handleHelp(player) {
  const gm = isGM(player);
  const lines = [
    '§6§l=== TTRPG VTT ===§r',
    '§7Items (use/right trigger):§r',
    '  §eDice Bag §7(Book)§r — dice roller',
    '  §bInitiative Token §7(Compass)§r — roll initiative',
    gm ? '  §6GM Wand §7(Blaze Rod)§r — GM control panel' : null,
    '',
    '§7Chat commands:§r',
    '§b!kit§r — get your Dice Bag + Initiative Token',
    '§b!roll §e<dice>§r — 1d20, 2d6+3, d20 adv/dis',
    '§b!init roll §e[mod]§r — roll initiative',
    '§b!init list§r — show order',
    gm ? '§b!init add/start/next/clear§r — manage combat' : null,
    gm ? '§b!spawn §e<type> [name]§r — spawn entity' : null,
    gm ? '§b!gm add/remove §e<player>§r — manage GMs' : null,
    !gm ? '§b!gm claim§r — claim GM (if none exists)' : null,
  ].filter(Boolean);
  player.sendMessage(lines.join('\n'));
}
