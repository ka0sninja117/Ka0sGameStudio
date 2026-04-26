import { Player, ItemStack, world } from '@minecraft/server';
import { GM_TAG } from './gm';

export const ITEM_DICE_BAG    = '§eDice Bag§r';
export const ITEM_INITIATIVE  = '§bInitiative Token§r';
export const ITEM_GM_WAND     = '§6GM Wand§r';

export function givePlayerKit(player: Player): void {
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

export function giveGMKit(player: Player): void {
  const inv = player.getComponent('minecraft:inventory');
  if (!inv) return;

  const wand = new ItemStack('minecraft:blaze_rod', 1);
  wand.nameTag = ITEM_GM_WAND;

  inv.container?.addItem(wand);
  player.sendMessage('§6[TTRPG]§r Received: §6GM Wand§r');
}
