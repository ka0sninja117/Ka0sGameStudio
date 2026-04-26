import { world, system, Player } from '@minecraft/server';
import { parseDiceNotation, formatDiceResult } from './dice';
import { InitiativeTracker } from './initiative';
import { GM_TAG, isGM, hasAnyGM } from './gm';
import { ITEM_DICE_BAG, ITEM_INITIATIVE, ITEM_GM_WAND, givePlayerKit, giveGMKit } from './items';
import { openDiceBagForm, openInitiativeForm, openGMMenu } from './forms';

const initiative = new InitiativeTracker();

// ── Item use (controller-friendly UI) ─────────────────────────────────────────

world.beforeEvents.itemUse.subscribe(event => {
  if (event.source.typeId !== 'minecraft:player') return;
  const nameTag = event.itemStack?.nameTag;
  if (!nameTag) return;

  if (nameTag !== ITEM_DICE_BAG && nameTag !== ITEM_INITIATIVE && nameTag !== ITEM_GM_WAND) return;

  event.cancel = true;
  const player = event.source as Player;

  system.run(() => {
    if (nameTag === ITEM_DICE_BAG)   openDiceBagForm(player);
    if (nameTag === ITEM_INITIATIVE) openInitiativeForm(player, initiative);
    if (nameTag === ITEM_GM_WAND)    openGMMenu(player, initiative);
  });
});

// ── Chat commands (keyboard fallback) ────────────────────────────────────────

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
      default:
        player.sendMessage(`§cUnknown command: !${cmd} — type §b!help§c for commands.§r`);
    }
  });
});

// ── Command handlers ──────────────────────────────────────────────────────────

function handleRoll(notation: string, player: Player): void {
  if (!notation) {
    player.sendMessage('§cUsage: !roll <dice>  e.g. !roll 1d20+5  |  !roll 2d6  |  !roll d20 adv§r');
    return;
  }
  const result = parseDiceNotation(notation);
  if (!result) { player.sendMessage(`§cInvalid dice notation: "${notation}"§r`); return; }
  world.sendMessage(formatDiceResult(result, player.name));
}

function handleInit(args: string[], player: Player): void {
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
      if (!first) { player.sendMessage('§cAdd combatants first.§r'); return; }
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
    case 'list': case 'show':
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

function handleGM(args: string[], player: Player): void {
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
    const target = world.getAllPlayers().find(p => p.name.toLowerCase() === (args[1] || '').toLowerCase());
    if (!target) { player.sendMessage(`§cPlayer "${args[1]}" not found.§r`); return; }
    target.addTag(GM_TAG);
    giveGMKit(target);
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

function handleSpawn(args: string[], player: Player): void {
  if (!isGM(player)) { player.sendMessage('§cGM only.§r'); return; }
  const rawType = args[0];
  if (!rawType) {
    player.sendMessage('§cUsage: !spawn <entity_type> [display_name]§r');
    return;
  }
  const entityType = rawType.includes(':') ? rawType : `minecraft:${rawType}`;
  const label = args.slice(1).join(' ') || null;
  try {
    const loc = player.location;
    const dir = player.getViewDirection();
    const entity = player.dimension.spawnEntity(entityType, {
      x: loc.x + dir.x * 3,
      y: loc.y,
      z: loc.z + dir.z * 3,
    });
    if (label) entity.nameTag = label;
    player.sendMessage(`§aSpawned §e${label || entityType}§a.§r`);
  } catch {
    player.sendMessage(`§cFailed to spawn "${entityType}".§r`);
  }
}

function handleKit(args: string[], player: Player): void {
  if (args.length === 0) {
    givePlayerKit(player);
    return;
  }
  if (!isGM(player)) { player.sendMessage('§cOnly GMs can give kits to others.§r'); return; }
  const target = world.getAllPlayers().find(p => p.name.toLowerCase() === args[0].toLowerCase());
  if (!target) { player.sendMessage(`§cPlayer "${args[0]}" not found.§r`); return; }
  givePlayerKit(target);
}

function handleHelp(player: Player): void {
  const gm = isGM(player);
  const lines: string[] = [
    '§6§l=== TTRPG VTT ===§r',
    '§7Items (controller-friendly):§r',
    '  §eDice Bag §7(Book)§r — opens dice roller form',
    '  §bInitiative Token §7(Compass)§r — rolls your initiative',
    gm ? '  §6GM Wand §7(Blaze Rod)§r — opens GM control panel' : '',
    '',
    '§7Chat commands:§r',
    '§b!kit§r — receive your Dice Bag + Initiative Token',
    '§b!roll §e<dice>§r — 1d20, 2d6+3, d20 adv, d20 dis',
    '§b!init roll §e[mod]§r — roll initiative',
    '§b!init list§r — show initiative order',
    gm ? '§b!init add §e<name> <val>§r  |  §b!init start  |  !init next  |  !init clear' : '',
    gm ? '§b!spawn §e<type> [name]§r — spawn entity' : '',
    gm ? '§b!gm add/remove §e<player>§r — manage GMs' : '',
    !gm ? '§b!gm claim§r — claim GM role (if none exists)' : '',
  ].filter(s => s !== '');
  player.sendMessage(lines.join('\n'));
}
