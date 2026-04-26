import { Player, world } from '@minecraft/server';
import { ActionFormData, ModalFormData } from '@minecraft/server-ui';
import { parseDiceNotation, formatDiceResult } from './dice';
import { InitiativeTracker } from './initiative';
import { GM_TAG, isGM } from './gm';

const DICE_LABELS = ['d4', 'd6', 'd8', 'd10', 'd12', 'd20', 'd100'];
const DICE_SIDES  = [  4,    6,    8,    10,    12,    20,    100  ];
const D20_INDEX   = 5;

export async function openDiceBagForm(player: Player): Promise<void> {
  const form = new ModalFormData()
    .title('Roll Dice')
    .dropdown('Dice Type', DICE_LABELS, D20_INDEX)
    .slider('Number of Dice', 1, 10, 1, 1)
    .slider('Modifier', -10, 10, 1, 0)
    .toggle('Advantage  (d20 only)', false)
    .toggle('Disadvantage  (d20 only)', false);

  const res = await form.show(player);
  if (res.canceled || !res.formValues) return;

  const [diceIdx, count, modifier, advantage, disadvantage] =
    res.formValues as [number, number, number, boolean, boolean];

  const sides = DICE_SIDES[diceIdx];
  let notation = `${count}d${sides}`;
  if (modifier !== 0) notation += modifier > 0 ? `+${modifier}` : `${modifier}`;
  if (sides === 20 && advantage && !disadvantage) notation += ' adv';
  if (sides === 20 && disadvantage && !advantage) notation += ' dis';

  const result = parseDiceNotation(notation);
  if (result) world.sendMessage(formatDiceResult(result, player.name));
}

export async function openInitiativeForm(player: Player, initiative: InitiativeTracker): Promise<void> {
  const form = new ModalFormData()
    .title('Roll Initiative')
    .slider('Initiative Modifier', -10, 10, 1, 0);

  const res = await form.show(player);
  if (res.canceled || !res.formValues) return;

  const [mod] = res.formValues as [number];
  const roll = Math.floor(Math.random() * 20) + 1;
  const total = roll + mod;
  const modStr = mod > 0 ? `+${mod}` : mod < 0 ? `${mod}` : '';
  initiative.add(player.name, total, true, player.id);
  world.sendMessage(`§b${player.name}§r rolled initiative: [${roll}]${modStr} = §e§l${total}§r`);
  world.sendMessage(initiative.formatList());
}

export async function openGMMenu(player: Player, initiative: InitiativeTracker): Promise<void> {
  if (!isGM(player)) { player.sendMessage('§cGM only.§r'); return; }

  const form = new ActionFormData()
    .title('GM Controls')
    .button('Next Turn')
    .button('Show Initiative')
    .button('Start Combat')
    .button('Add Combatant')
    .button('Spawn Entity')
    .button('Clear Initiative')
    .button('Manage GMs');

  const res = await form.show(player);
  if (res.canceled) return;

  switch (res.selection) {
    case 0: {
      const entry = initiative.next();
      if (!entry) { player.sendMessage('§cTracker is empty.§r'); return; }
      world.sendMessage(`§6[Initiative]§r §e§l${entry.name}§r's turn! (${entry.value})`);
      break;
    }
    case 1:
      world.sendMessage(initiative.formatList());
      break;
    case 2: {
      const first = initiative.start();
      if (!first) {
        player.sendMessage('§cAdd combatants first — have players use the Initiative Token, or use Add Combatant.§r');
        return;
      }
      world.sendMessage('§6§l=== COMBAT BEGINS ===§r');
      world.sendMessage(initiative.formatList());
      world.sendMessage(`§6[Initiative]§r §e§l${first.name}§r, you're up!`);
      break;
    }
    case 3:
      await openAddCombatantForm(player, initiative);
      break;
    case 4:
      await openSpawnForm(player);
      break;
    case 5:
      initiative.clear();
      world.sendMessage('§6[Initiative]§r Combat ended. Tracker cleared.');
      break;
    case 6:
      await openManageGMsForm(player);
      break;
  }
}

async function openAddCombatantForm(player: Player, initiative: InitiativeTracker): Promise<void> {
  const form = new ModalFormData()
    .title('Add Combatant')
    .textField('Name', 'e.g. Goblin Archer')
    .slider('Initiative Value', 1, 30, 1, 15);

  const res = await form.show(player);
  if (res.canceled || !res.formValues) return;

  const [name, value] = res.formValues as [string, number];
  if (!name.trim()) { player.sendMessage('§cName cannot be empty.§r'); return; }
  initiative.add(name.trim(), value, false);
  world.sendMessage(`§6[Initiative]§r Added §e${name.trim()}§r (${value})`);
  world.sendMessage(initiative.formatList());
}

async function openSpawnForm(player: Player): Promise<void> {
  const form = new ModalFormData()
    .title('Spawn Entity')
    .textField('Entity Type', 'zombie, skeleton, spider, creeper...')
    .textField('Display Name  (optional)', 'e.g. Goblin Guard', '');

  const res = await form.show(player);
  if (res.canceled || !res.formValues) return;

  const [rawType, displayName] = res.formValues as [string, string];
  if (!rawType.trim()) { player.sendMessage('§cEntity type cannot be empty.§r'); return; }

  const entityType = rawType.trim().includes(':') ? rawType.trim() : `minecraft:${rawType.trim()}`;
  const label = displayName.trim() || null;

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
    player.sendMessage(`§cFailed to spawn "${entityType}". Check the entity type name.§r`);
  }
}

async function openManageGMsForm(player: Player): Promise<void> {
  const others = world.getAllPlayers().filter(p => p.id !== player.id);
  if (others.length === 0) { player.sendMessage('§7No other players online.§r'); return; }

  const form = new ActionFormData().title('Manage GMs — tap to toggle');
  for (const p of others) {
    const tag = isGM(p) ? '§a[GM] §r' : '§7[Player] §r';
    form.button(`${tag}${p.name}`);
  }

  const res = await form.show(player);
  if (res.canceled) return;

  const target = others[res.selection!];
  if (!target) return;

  if (target.hasTag(GM_TAG)) {
    target.removeTag(GM_TAG);
    target.sendMessage('§eYour GM privileges have been removed.§r');
    world.sendMessage(`§6[TTRPG]§r §e${target.name}§r is no longer a GM.`);
  } else {
    target.addTag(GM_TAG);
    target.sendMessage('§6§lYou have been granted GM privileges!§r');
    world.sendMessage(`§6[TTRPG]§r §e${target.name}§r is now a GM.`);
  }
}
