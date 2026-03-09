# Ka0s Game Studio

A collection of web-based games built with pure HTML/CSS/JavaScript (no frameworks, no build tools). Each game is a single self-contained HTML file.

## Games

| Game | File | Description |
|------|------|-------------|
| **Mutagen** | `Mutagen.html` | Dark survival game — "Survive. Adapt. Consume." (primary active project) |
| **Game the Game** | `Game the Game.html` | Secondary game |
| **Submarine Bridge** | `Submarine current.html` | Submarine simulator |

Landing page: `index.html`

---

## Mutagen — Design Document

**Current Version:** v1.17

### Concept
Top-down 2D survival game where the player is a mutant creature that evolves by consuming NPCs. Right-click to move and attack. Canvas-rendered, 2400x1800 world with camera/zoom system.

### Player Progression

**Evolution Levels** (4 tiers):
1. **Grub** (Level 1) — XP threshold: 0
2. **Scavenger** (Level 2) — XP threshold: 10
3. **Predator** (Level 3) — XP threshold: 100
4. **Abomination** (Level 4) — XP threshold: 1000

Stats scale exponentially per level (HP, Attack, Defense, Stamina, Stealth, Speed). Each level also increases `playerSize` visually.

### Genetic Origins (Character Selection at Game Start)
| Origin | Emoji | Stat Bonus | Starting Ability |
|--------|-------|------------|-----------------|
| Arachnoid | 🦂 | +10% Attack & Defense gains | Burrow |
| Reptilian | 🦎 | +10% Stealth & Speed gains | Dash |
| Raptorial | 🦖 | +10% Speed & Attack gains | Pounce |

Origin bonuses are multiplicative percentage boosts applied whenever the player gains stats from loot.

### Abilities

**Level 1 Active Abilities** (one granted by origin, others available from loot):
- **Pounce** (🏃) — Leap toward mouse, collision damage on landing. 5s cooldown.
- **Dash** (💨) — Fast movement that damages enemies passed through. 5s cooldown.
- **Burrow** (🕳️) — Go underground for up to 5s, reduced speed, near-invisible. 5s cooldown.

**Level 2 Abilities** (unlocked from Level 2+ NPC loot drops, 70% chance):
- **Tremor Sense** (〰️) — Toggle on/off. Detects NPCs within 600px (Lv1) / 700px (Lv2) with radial indicators. Cooldown: 5s (Lv1) / 1.25s (Lv2). Max active duration: 5s.
- **Spike Launch** (▲) — Fires a projectile toward mouse. 250px range, 80% melee damage, 5px/frame speed, 2s cooldown.

**Level 2 Upgrades** (for Level 1 abilities):
- Pounce Lv2: 140px distance, 260ms duration, 1.25x landing damage, 3.5s cooldown
- Dash Lv2: 325px distance, 2.75x speed, 1.5x pass-through damage, 3.5s cooldown
- Burrow Lv2: 7.5s duration, 0.7x speed, more hidden (0.2 alpha), 60px emerge burst damage, 3.5s cooldown

### Loot System
- Killing NPCs drops loot (bones icon at death location)
- Right-click bones within 30px to open loot popup
- Loot offers: passive stat upgrades + active ability unlock/upgrade
- Already-owned abilities are greyed out in the selection UI
- Player can decline loot

### NPC System
- **Types:** Ladybug (🐞), Spider (🕷️), Butterfly (🦋)
- **Levels:** 1–4, scaling with player level
- **Max on map:** 5 simultaneously
- **AI behaviors:** Patrol (random wandering), Chase (pursuit when player detected), Combat (melee engagement)
- **Spawn system:** Exponential decay weighting (`NPC_SPAWN_DECAY = 0.44`). NPCs 2+ levels above player never spawn. Distribution shifts gradually with XP progress, not just at level thresholds.
- **XP rewards:** Level 1→1 XP, Level 2→10 XP, Level 3→100 XP, Level 4→1000 XP

### Combat
- Right-click an NPC to target and attack
- 500ms base attack cooldown
- First-strike delay: 250ms for the defender's first retaliation
- Attack lurch animation (150ms, 8px)
- Floating damage text
- Overkill damage tracking on game over screen
- Player does NOT auto-attack when an NPC initiates combat (PR #12)

### UI Elements
- **HUD:** HP bar, Hunger bar, XP bar, evolution level display, version number
- **Hotbar:** Shows unlocked abilities with cooldown indicators, keybinds (1-5)
- **Map mode:** Toggle to see full world overview
- **Admin panel:** Debug panel to modify stats/abilities mid-game
- **Game over screen:** Kill count, survived time, stats summary

### Key Design Decisions (from development history)
- All games are single-file HTML — no external dependencies, no build process
- NPC spawn uses exponential decay (not tier-based) for smooth difficulty curve
- Movement abilities save/restore pre-ability targeting state
- Tremor Sense uses toggle mode (on/off) rather than hold-to-activate
- Spike projectiles render in world-space coordinates with proper camera transforms

### Development Changelog (PR History)
- **PR #1–2:** Initial bug fixes (4 critical + 4 gameplay bugs)
- **PR #3:** Merge fixes
- **PR #4:** Overkill damage display, dash reset, post-ability targeting restore
- **PR #5:** Level 2 upgrades for Pounce, Dash, Burrow; death timer freeze
- **PR #6:** Grey out already-selected abilities in loot screen
- **PR #7:** Restore Level 1 ability offers in loot screen
- **PR #8:** NPC patrol/chase/combat AI for Level 1 and Level 2
- **PR #9:** Level 2 NPC detection fix (dedicated scan loop, 400px radius)
- **PR #10:** NPC spawn rework — exponential decay weighted system
- **PR #11:** Hard-cap NPC spawn range (zero weight for 2+ levels above player)
- **PR #12:** Remove player auto-attack when NPC initiates combat
- **PR #13:** Tremor Sense ability (Level 2+ NPC loot)
- **PR #14:** Tremor Sense admin panel fix
- **PR #15:** NPC level split and version display (v1.15)
- **PR #16:** Tremor Sense radial indicator effect (v1.15)
- **PR #17:** Tremor Sense cooldown, duration, level 2 upgrades, hotbar indicator (v1.16)
- **PR #18:** Version bump to v1.17
- **PR #19:** Spike Launch level 2 ability added to Reflexes/Dash tree
- **PR #20:** Spike Launch rendering fix (world-space transform, speed reduction)
- **PR #21:** Spike speed 5px/frame, Tremor Sense ring visibility
- **PR #22:** Tremor Sense ring fix (2px, globalAlpha reset), spike damage to 80%
- **PR #23:** Tremor Sense toggle mode (on/off instead of hold)
