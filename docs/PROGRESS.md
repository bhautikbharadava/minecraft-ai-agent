# Project Progress Tracker

Living document. Update it whenever a milestone lands, gets scrapped,
or gets scoped. The PRD (`docs/PRD.md`) stays the requirements source;
this file tracks reality.

Last updated: after PR #9 merge + kits/base/storage/auto-stash work on
master.

---

## Milestone status

| Milestone | Scope (PRD ref) | Status | Delivered in |
|---|---|---|---|
| M1 Foundation | §17 | ✅ Done | PR #2 |
| M2 Goal lifecycle | §8, §9 | ✅ Done | PR #3 |
| M3 First execution | §16 UC-01 | ✅ Done | PR #4 |
| M4 Direct acquisition | §17 M4 | ✅ Done | PR #5 |
| M5 Dependency planning | §10, §17 M5 | ✅ Done | PR #6 |
| M6 Crafting tables | §17 M6 | ✅ Done | PR #7 |
| M7 Smelting | §16 UC-06, §17 M7 | ✅ Done | PR #7 |
| Tool ladders | (extension of §10) | ✅ Done | PR #7 |
| Torch lighting | (survival prevention) | ✅ Done | PR #8 |
| M8 Survival interrupts | §15, §16 UC-07 | ✅ Done | PR #8 |
| Emergency food foraging | (M8 extension) | ✅ Done | PR #8 |
| Drowning guard | (M8 extension) | ✅ Done | PR #9 |
| M9 Exploration - biomes | §16 UC-08 | ✅ Done | PR #8 |
| M9 Exploration - biome-gated resources | (M9 extension) | ✅ Done | PR #8 |
| M9 Exploration - structures | §17 M9 | ✅ Done | PR #9 |
| M9 Exploration - caves | §17 M9 | ⬜ Backlog | - |
| UC-09 Kits (armor/tool sets) | §16 UC-09 | ✅ Done | master |
| Base camp + storage + auto-stash | (M11 groundwork) | ✅ Done (persisted) | master |
| Restock from base | `/agent restock torches/food/<item>` | ✅ Done | master |
| Base-supply crediting in plans | goals started at base use stored items | ✅ Done | master |
| Enchanting E1 XP sensing | [ENCHANTING.md](ENCHANTING.md) | ✅ Done | feat/enchanting |
| Enchanting E5 enchant goal (acts: item + lapis + table + enchant) | [ENCHANTING.md](ENCHANTING.md) | ✅ Done, verified in-game | feat/enchanting |
| Enchanting E3 leather (cow hunting) | [ENCHANTING.md](ENCHANTING.md) | ✅ Done | feat/enchanting |
| Enchanting E2 XP farming | [ENCHANTING.md](ENCHANTING.md) | ✅ Done (ore mining; low levels only) | feat/enchanting |
| Enchanting E6/E7 (bookshelf ring, villager trading) | [ENCHANTING.md](ENCHANTING.md) | ⬜ Designed, not built | - |
| M10 Building foundation | §17 M10 | ⬜ Backlog | - |
| M11 Farm building | §16 UC-11, §17 M11 | ⬜ Backlog | - |
| M12 Natural language | §18, §17 M12 | ⬜ Backlog | - |

---

## Commands (in-game)

```text
/agent status                        world/inventory snapshot + executor state
/agent get <item> <count>            single-item goal, recursive planning
/agent get <kit> <count>             kit goal: iron_armor, diamond_armor,
                                     iron_tools, diamond_tools (UC-09 pooling)
/agent explore <biome>               travel to a biome, verified arrival
/agent explore <structure>           village, mineshaft, stronghold,
                                     shipwreck, ruined_portal, buried_treasure
/agent base here                     set home anchor; place chest + table
/agent stash <item> [count|all]      store items at the base chest
/agent stash junk [all]              dump tunnel by-products preset
/agent enchant <item> [level]        enchant goal: secures item + lapis,
                                     builds a permanent enchanting table
                                     at base, walks back to it, enchants
/agent build [blueprint]             raise a structure from a blueprint
/agent resume                        pick the last goal back up, or unstick
                                     a run whose action wedged
/agent goal                          live goal progress
/agent cancel                        stop goal + navigation
```

## Autonomous behaviors

- Recursive planning: mine → craft → smelt chains with real recipes
- Tool ladders: self-crafts pickaxes needed for gated ores
- Resource pooling: multi-root plans (kits) gather shared dependencies once
- Crafting tables / furnaces: walked to when present, else carried/placed
  (one per plan), collected afterwards
- Torch upkeep: below 16 torches, crafts up to a full stack (64) before
  digging; drops torches continuously while light < 8
- Survival interrupts (every 0.5 s): low health/hunger -> eat/wait; low
  oxygen -> swim up; starving with no food -> forage berries/mushrooms;
  all resume the interrupted action afterwards
- Hunting: items with no source block (leather, raw meat) route to a
  hunt - roam to find a herd, strike, collect the drops, repeat until
  the inventory count is met. Always leaves 2 animals alive so the local
  herd is never wiped
- Breeding: when the breeding food is carried (wheat for cows/sheep,
  carrots for pigs, seeds for chickens), the plan grows the herd before
  harvesting it
- Farming: crops are grown, not mined - gather seeds from grass, craft a
  hoe, walk home, till soil beside water, sow, ripen (bone meal when
  carried), reap. The plot is base infrastructure like the chest and
  enchanting table: recorded in BaseSavedState and returned to, not a
  field left wherever the agent happened to stand. This closes the
  leather cycle with no village needed:
  seeds -> wheat -> breed cows -> leather
- Auto-stash: free slots < 3 mid-goal -> detour to base chest, dump junk
  list, resume
- Death: goal fails honestly with death location (loot drops vanilla-style)

---

## Known limitations

- Water crossings are swim-only; boat sailing was scrapped (see decisions)
- Combat is melee-only v0 (no bow/shield/flee); agent strikes nearby hostiles
- Enchanting self-provisions the item, lapis, leather and the table.
  `/agent enchant <item>` spends whatever XP is on hand; passing a level
  farms the shortfall by mining coal ore. **Ore XP is slow** (level 30 =
  1395 points, roughly a thousand ore blocks), so high level targets give
  up with a progress report rather than digging forever - a mob grinder
  is the realistic route and is not built. Bookshelf rings are not placed,
  so offers stay in the low range
- Crops grow on the world clock: without bone meal a wheat harvest takes
  minutes of real time, and the agent waits through it rather than doing
  other work. Bone meal (when carried) skips the wait
- Farming needs soil within 4 blocks of water; away from water the plan
  fails honestly rather than tilling ground that will dry out
- No pen or enclosure is built; bred animals wander freely
- Obsidian is MADE reliably (water on lava, water reclaimed afterwards)
  but HARVESTING it is unreliable: the block forms in the lava surface
  and navigation often reports "unable to find any path to obsidian".
  Bridging to an awkward block is not implemented
- Farming and blueprint building are proof-of-concept: neither has been
  seen through a full cycle in the client
- Netherite unreachable (smithing + nether not implemented)
- Auto-restock before goals is command-driven only (`/agent restock`);
  automatic pre-goal restocking is a future refinement
- Smelting supports plain furnace + coal fuel only (no blast furnace,
  lava, other fuels); one recipe input per run
- Structure search limited to vanilla-tagged names
- Kit pieces are fixed (no custom sets); netherite gear excluded
- Food automation is reactive (eat/forage when starving), never proactive

---

## Improvement backlog (prioritized)

| # | Item | Notes | Effort | Status |
|---|---|---|---|---|
| 1 | Restock from base | `/agent restock torches\|food\|<item> [count]` | S | ✅ |
| 2 | Base persistence | BaseSavedState via overworld SavedDataStorage | S | ✅ |
| 3 | Combat v0 | melee engage of nearby hostiles with best sword; flee still future | M | ✅ v0 |
| 4 | Proactive food economy | cook hunted meat via furnace pipeline; berry upkeep | M | ⬜ |
| 5 | M10 Building foundation | blueprint placement + structure verification | M-L | ⬜ |
| 6 | Kit expansion | gold/mixed kits ("starter kit") | S | ⬜ |
| 7 | Cave exploration | exposed-ore seeking, y-level targeting | L | ⬜ |
| 8 | Enchanting | XP tracking + table interaction + book chain; see [ENCHANTING.md](ENCHANTING.md). Verified end to end in-game: obsidian from lava -> table -> enchant | L | ✅ |
| 9 | Nether progression | portals, fortresses, netherite (UC-10 endgame) | XL | ⬜ |
| 10 | Natural language | parser/LLM front-end (§18) | L | ⬜ |

Effort: S small, M medium, L large, XL extra-large.

---

## Scrapped ideas (do not rebuild without new thinking)

- **Boat sailing** (`/agent sail x z`): boat entities spawned at the
  agent's feet sit on shallow-water bottoms; ridden boat physics are
  rider-client-authoritative so server-side steering cannot work, and
  client-side piloting needs its own vehicle-control layer. Revisit
  only as a dedicated vehicle-control milestone.

---

## Design decisions log

- **Server-authoritative equipping**: tool swaps mutate the integrated
  server's inventory copy; vanilla syncs slots/carried item to the
  client. Client-side mutation desyncs drop calculation (root cause of
  "mines but nothing drops" bug).
- **Tier guard on equip**: if held pickaxe tier already satisfies the
  gate, never switch - avoids fighting Baritone auto-tool and resetting
  break progress.
- **Movement counts as mining progress**: long vein searches are not
  restarted by the idle timer; restarts only when stationary AND
  dropless.
- **Gather-before-place**: all mining steps precede utility-block
  placement in plans, so Baritone never digs through freshly placed
  blocks.
- **Satisfaction-first recovery**: every failure re-checks live goal
  completion before reacting (PRD 14).
- **Best-effort cleanup**: collect-steps never cost replan attempts nor
  fail satisfied goals.
- **In-plan production credit**: multi-root plans pool shared
  intermediates (UC-09); sibling branches never re-gather.
- **Smelting preferred over crafting** for smeltable outputs: their
  crafting routes are closed nugget loops. Accepted inputs are unioned
  across duplicate vanilla recipes and the planner picks whichever is
  actually gatherable.
- **Framework-free cores + seam adapters**: planner/goal logic uses no
  Minecraft classes; every world touch goes through interfaces
  (locators, sensors, crafters, sailors...) implemented in the
  integration package and faked in JVM smoke checks.
- **JVM smoke checks over unit tests**: `gradle build` runs
  deterministic scenario validation (AgentCli validate) without a
  game instance.
