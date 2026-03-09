# Ka0sGameStudio — Claude Context

## Project Overview
Browser-based top-down survival/RPG game. The primary game file is `Mutagen.html` (~4900 lines, single-file HTML/CSS/JS canvas game). Other files (`Game the Game.html`, `Submarine current.html`, `index.html`) are separate projects and should not be modified unless explicitly requested.

## Versioning Convention
Version number matches pull request number. Current version: **v1.17** (PR #17).
- Version is set in `Mutagen.html` as `const GAME_VERSION = 'v1.XX';` near the top of the `<script>` block.
- Bump the version on each PR/commit batch.

## Git Workflow
- Development branch: `claude/check-mutagen-setup-hFKJn`
- Push to that branch; user merges to `main` via pull request.
- `main` is what GitHub Pages serves.

## Game Architecture (Mutagen.html)
- **Canvas-based rendering** — single `<canvas>` element, drawn every frame via `requestAnimationFrame`.
- **World coordinates** vs **canvas coordinates**: The render loop applies `ctx.translate(-cameraX * zoomScale, -cameraY * zoomScale)` + `ctx.scale(zoomScale, zoomScale)` inside a `ctx.save()/ctx.restore()` block. All world-space objects (player, NPCs, projectiles) must use world coordinates inside that block. UI elements (hotbar, info panel, radial indicator) are drawn after `ctx.restore()` in canvas/screen space.
- **zoomScale = 1** during normal play; shrinks to fit world in map mode.
- **playerSize = 16** world units (base). Scales with level.
- **basePlayerSpeed = 2** px/frame.

## Ability System

### playerAbilities object
```js
let playerAbilities = { pounce: 0, dash: 0, burrow: 0, tremorSense: 0, spikeLaunch: 0 };
```
Level 0 = not learned, 1 = learned, 2 = upgraded.

### abilityDetailsMap
Used for hotbar assignment. Each entry: `{ name, key, hotbarIcon }`.

### Hotbar slots
7 slots keyed F, R, E, Q, C, X, Z. Primaries (pounce/dash/burrow) prefer F/R. Secondary level-2 abilities (tremorSense, spikeLaunch) prefer E/Q.

### Cooldowns
`cooldowns` object: `{ abilityKey: { lastUsed: 0, duration: ms } }`. Helpers: `isAbilityOnCooldown()`, `startCooldown()`, `getCooldownRemaining()`.

### Damage
All damage goes through `dealDamage(attacker, defender, currentTime)`. Effective damage = `attacker.attack - defender.defense`. To deal X% of melee effective damage via a projectile:
```js
const attack = playerStats.attack * mult + npc.defense * (1 - mult);
dealDamage({ attack, isPlayer: true, ... }, npc, currentTime);
```

### Ability unlock flow
- Loot popup offers active ability from the passive tree chosen.
- `presentActiveAbilityChoice()` can substitute a secondary ability (e.g. tremorSense replaces burrow at 70% chance from lv2+ NPC).
- `updateHotbarSlot()` assigns the ability and syncs cooldown duration.

## Implemented Abilities

### Tremor Sense (Burrow/Instinct tree, Lv2)
- **Toggle** on/off (press to activate, press again to deactivate early). Cooldown starts on deactivation.
- Max active duration: 5000ms. Cooldown: 5000ms (Lv1), 1250ms (Lv2).
- While active: draws precise red NPC indicator arcs + a 2px yellowish-grey ring (`rgba(210,195,110,0.5)`) around the player at `STATIC_RADIAL_INDICATOR_RADIUS`.
- Movement speed reduced to 50% while active.
- State: `isTremorSenseActive`, `tremorSenseStartTime`, `tremorSenseNpcData[]`.

### Spike Launch (Reflexes/Dash tree, Lv2)
- **Instant fire** toward mouse direction at time of keypress.
- Speed: 5 px/frame. Range: 250px. Cooldown: 2000ms.
- Damage: 80% of effective melee damage — `attack = playerStats.attack * 0.8 + npc.defense * 0.2`.
- Projectile array: `spikeLaunchProjectiles[]` — each entry `{ x, y, angle, distanceTravelled }`.
- Rendered in world space as an orange spiky triangle: `sz = playerSize / 4` world units.
- Offered in place of Dash at 70% chance from lv2+ NPC loot.

## Rendering Order (per frame)
1. Clear canvas / fill background
2. `ctx.save()` + world transform
3. Forest background
4. Player
5. NPCs
6. Spike Launch projectiles
7. Dead NPCs (loot circles)
8. `ctx.restore()`
9. `drawTargetedNpcIndicator()`
10. UI: `drawPlayerInfoPanel()`, `drawHotbar()`
11. Radial NPC indicator ring (canvas space, uses `playerCanvasX/Y`)
12. Game over overlay (if applicable)
13. Floating damage texts
14. `drawStatusBarMessage()`
