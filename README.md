# Minecraft AI Agent

A Fabric mod foundation for a future autonomous Minecraft AI agent.

## Current milestone

Implemented:

- Fabric/Gradle project structure.
- Main mod entry point: `com.bhautik.mcagent.McAgent`.
- `/agent status` command.
- World-state collection for player health, hunger, position, and dimension.
- Inventory-state collection with item-count summaries.
- Goal lifecycle (`IDLE → ACTIVE → SUCCESS / FAILED / CANCELLED`) with
  `GetItemGoal`, goal manager, and item registry.
- Action model (`PENDING → RUNNING → SUCCESS / FAILED / CANCELLED`)
  with `MineAction`, an idle-timeout retry ladder, and safe cancellation.
- In-game commands: `/agent get <item> <count>`, `/agent goal`,
  `/agent cancel`.
- **First execution (M3):** get-item goals are planned into `MineAction`s
  and executed through the Baritone integration layer, with inventory
  monitored live every second and verified independently before SUCCESS.
- **General direct acquisition (M4):** every real vanilla item resolves
  by name; ~60 directly-mineable/gatherable items have curated source
  blocks (ores + deepslate, wood family, plants); tool-tier gating fails
  fast (e.g. diamonds without an iron pickaxe) instead of mining blocks
  that would drop nothing.
- **Dependency planning (M5):** goals expand recursively through real
  vanilla recipes — `/agent get crafting_table 1` mines logs, crafts
  planks, then crafts the table. Shared dependencies are aggregated,
  existing inventory is credited first, and inventory-grid (2x2)
  recipes execute via a server-side `CraftAction` with output
  verification.
- **Crafting table (M6):** recipes wider or taller than 2x2 plan
  acquire-table → place → craft: the agent carries or crafts a
  `crafting_table`, places it on a verified spot next to itself
  (`PlaceBlockAction`, block-state verified), and crafts 3x3 recipes
  against it — `/agent get chest 1` works end-to-end from bare hands,
  as do `furnace`, `stone_pickaxe`, and other table-gated goods whose
  ingredients are reachable. Placement is skipped when a table is
  already within range, one placement is shared per plan, and gated
  crafts fail honestly if the table disappears mid-run.
- **Table reuse:** a placed table is remembered by world query, not
  inventory — if one exists within walking distance (~48 blocks) the
  planner emits `MoveAction` (Baritone goto) to reach it instead of
  crafting another; only when none exists nearby does the agent build
  one. Arrival is verified against live distance (PRD 13). Tables the
  agent placed itself are collected afterwards (`BreakBlockAction`,
  verified by cleared block state + returned item); pre-existing
  tables are left untouched.
- **Smelting (M7):** furnace recipes route through real furnaces —
  `/agent get iron_ingot 1` plans mine raw iron, mine coal, place a
  carried/crafted furnace, load both into the furnace's block entity,
  and let it cook on real game ticks while the action monitors and
  harvests output (PRD UC-06: never block the thread). Furnaces are
  walked to when present, placed when missing, and collected after.
  This completes the iron ladder: `/agent get iron_pickaxe 1` works
  end-to-end from bare hands (logs → table → wooden pickaxe → stone →
  stone pickaxe → iron ore → coal → smelt → iron pickaxe), and
  `/agent get diamond_pickaxe 1` self-provides its entire prerequisite
  chain including the iron pickaxe needed to mine diamond ore.
- **Survival interruptions (M8):** every half second the active run is
  assessed (health ≤ 4 hearts or hunger ≤ 3 suspends work). On
  emergency the current action is *paused* — navigation stopped, state
  kept — re-queued, and a recovery step jumps the queue: eat the most
  nutritious food carried, wait out the danger, then resume where it
  left off. Starving with no food fails the goal honestly after a
  timeout instead of mining until death.
- **Exploration (M9, biomes):** `/agent explore desert` verifies the
  current biome first (already there = instant success), then wanders
  outward through Baritone's explore process until the live biome
  sensor reports arrival — verified independently, with re-issue on
  stalls and an honest failure when the budget runs out.
- **Torch lighting (survival prevention):** mining plans keep a full
  stack of 64 torches — whenever stock drops below 16, the planner
  prepends the whole coal + sticks chain before any digging — and
  every mining step is followed by a best-effort `Light area`
  action that drops a torch whenever the agent stands below light
  level 8 — keeping tunnels unspawnable instead of reacting to the
  mobs afterwards.
- **Tool ladders:** when a mineable item needs a pickaxe tier the agent
  doesn't have, the planner folds the cheapest qualifying tool's whole
  chain into the plan instead of refusing — `/agent get stone_pickaxe 1`
  from bare hands mines logs, crafts planks/sticks/table, crafts and
  places the table, crafts the wooden pickaxe, then mines stone and
  crafts the stone pickaxe. If even that chain is unresolvable (e.g.
  diamonds needing an iron pickaxe while iron requires smelting), the
  refusal explains exactly which link failed.
- **Structure discovery (M9):** `/agent explore village` locates the
  nearest tagged structure via vanilla world lookup and walks there —
  arrival verified against live distance. Also searchable: mineshaft,
  stronghold, shipwreck, ruined_portal, buried_treasure.
- **Biome-gated gathering:** biome-locked resources chain exploration
  automatically — `/agent get cactus 8` from a plains start plans
  `[Explore to desert, Mine cactus]`, skipping the travel step when
  already standing in the right biome. Gated today: cactus (desert),
  bamboo (jungle), sweet berries (taiga), red sand (badlands),
  snowballs (snowy plains).

Not implemented yet: structure discovery, cave exploration, LLM
integration, or farms.

### Execution backend

Mining is delegated to [Baritone](https://github.com/cabaletta/baritone)
through a reflective integration layer: install Baritone alongside the mod
to enable autonomous mining. Without Baritone, goals fail fast with a
clear reason instead of pretending to work. Goals and planners never call
Baritone directly — only actions touch the integration layer, which can be
swapped for any navigation backend.

## Commands

```text
/agent status                    show world/inventory snapshot and executor state
/agent get <item> <count>        register a GetItemGoal and start executing it
/agent explore <biome>           travel to a biome and verify arrival (UC-08)
/agent goal                      report the active goal's live progress
/agent cancel                    cancel the active goal and stop navigation
```

Items resolve against the live vanilla registry (`diamond`,
`deepslate_diamond_ore`, `sweet_berries`, …). If inventory already
satisfies the request, the goal succeeds immediately. Otherwise:

```text
directly acquirable? ──no──► FAILED "no supported acquisition strategy"
       ↓ yes (+ tool check)
tool tier available? ──no──► FAILED "requires iron pickaxe or better"
       ↓ yes
Baritone mines → live progress → verified count → SUCCESS / FAILED
```

Smelted goods (e.g. `iron_ingot`) resolve but fail honestly until the
smelting milestone lands.

## Versions

- Minecraft: `26.2` (matches the `sandbox` launcher profile)
- Fabric Loader: `0.19.3`
- Fabric API: `0.158.0+26.2`
- Fabric Loom: `1.17.19` (`net.fabricmc.fabric-loom`, official names — no yarn mappings)
- Java: `25`

## Build

```bash
gradle build
```

Requires JDK 25.

The mod jar is generated under `build/libs/`.

## Run in development

```bash
gradle runClient
```

Once in a world, run:

```text
/agent status
/agent get cobblestone 64
/agent goal
/agent cancel
```

## Architecture

```text
com.bhautik.mcagent
├── McAgent
├── command/AgentCommand
├── action/AgentAction, ActionStatus, MineAction, CraftAction,
│         PlaceBlockAction
├── state/WorldState
├── state/WorldStateCollector
├── state/InventoryState
├── world/TableLocator           (is-a-table-in-range seam)
├── goal/GoalService        (agent brain: plan → execute → verify → recover)
├── goal/Goal               (planner-facing seam, pre-execution)
├── planner/Planner              (+ Environment: crafter/placer/locator seams)
├── executor/AgentExecutor  (single-action tick runner)
├── crafting/RecipeResolver, VanillaRecipeResolver,
│            VanillaCraftingExecutor
├── integration/VanillaPlacementExecutor (placement + table detection)
├── item/MineableItems      (directly-mineable item → source block map)
└── integration/BaritoneIntegration (reflective, swap-safe backend)

dev.minecraftai.agent         (framework-free goal lifecycle core)
├── goal/AgentGoal, GoalStatus, GetItemGoal, AgentGoalManager
├── item/MinecraftItem, ItemRegistry
├── world/InventoryState
└── command/AgentCommandHandler + AgentCli (JVM smoke checks)
```

Baritone is intentionally isolated behind `BaritoneIntegration`; this project does not embed or modify Baritone or Meteor Client code.
