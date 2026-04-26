import { Player, world } from '@minecraft/server';

export const GM_TAG = 'ttrpg_gm';

export function isGM(player: Player): boolean {
  return player.hasTag(GM_TAG) || player.isOp();
}

export function hasAnyGM(): boolean {
  return world.getAllPlayers().some(p => p.hasTag(GM_TAG));
}
