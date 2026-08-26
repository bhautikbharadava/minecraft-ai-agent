# Minecraft AI Agent

A Fabric mod foundation for a future autonomous Minecraft AI agent.

## Current milestones

Full per-milestone status, backlog, limitations, scrapped ideas, and the
design-decision log live in **[docs/PROGRESS.md](docs/PROGRESS.md)**.
Summary of what works today:

- **M1-M5**: foundation, goal lifecycle, mining via Baritone, ~60 curated
  direct acquisitions with tool-tier gating, recursive dependency planning
- **M6/M7**: crafting tables (walk-to / carry / place / collect) and
  smelting through real furnaces - `/agent get diamond_pickaxe 1` from
  bare hands self-provides everything including the iron pickaxe needed
  for diamonds
- **UC-09 kits**: `iron_armor`, `diamond_armor`, `iron_tools`,
  `diamond_tools` planned as one pooled goal
- **Survival**: interrupts for health/hunger/oxygen (eat / forage /
  surface), torch upkeep (full stack) with continuous tunnel lighting,
  meal upkeep (forages berries/mushrooms before long digs when food is
  low), death handling (goal fails fast with location), and melee
  combat v0 - engages hostiles in reach with the best carried sword
- **Exploration**: biome travel, structure discovery (village, mineshaft,
  stronghold, ...), biome-gated resources auto-chain exploration
- **Base camp**: anchor + chest + table; stash commands and an automatic
  junk-detour when the bag is nearly full. Goals started at base credit
  chest contents and finished furnace smelts as owned supplies - the
  plan withdraws them instead of re-mining

Not yet: combat, enchanting, nether/smithing, cave targeting, farms
(M10/M11), natural language (M12). See PROGRESS.md for the full backlog.

### Execution backend

Mining is delegated to [Baritone](https://github.com/cabaletta/baritone)
through a reflective integration layer: install Baritone alongside the mod
to enable autonomous mining. Without Baritone, goals fail fast with a
clear reason instead of pretending to work. Goals and planners never call
Baritone directly — only actions touch the integration layer, which can be
swapped for any navigation backend.

## Commands

```text
/agent status                       world/inventory snapshot + executor state
/agent get <item> <count>           recursive get-goal (mine/craft/smelt chains)
/agent get <kit> <count>            kit goal: iron_armor, diamond_armor,
                                    iron_tools, diamond_tools (UC-09 pooling)
/agent explore <biome|structure>    travel + verified arrival (desert, village,
                                    stronghold, mineshaft, shipwreck, ...)
/agent base here                    set home anchor; place chest + crafting table
/agent stash <item|junk> [count]    store items at the base chest
/agent goal                         live goal progress
/agent cancel                       stop goal + navigation
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
├── McAgent                          entry point: command + server-tick wiring
├── command/AgentCommand             /agent command tree
├── action/                          MineAction, CraftAction, SmeltAction,
│                                    MoveAction, ExploreAction, PlaceBlockAction,
│                                    BreakBlockAction, DepositAction,
│                                    RecoverAction, SurfaceAction,
│                                    Equipper, TunnelLighter seams
├── planner/Planner                  recursive multi-root planning (+Environment)
├── goal/GoalService                 agent brain: plan -> execute -> verify ->
│                                    survive -> recover
├── executor/AgentExecutor           single-action tick runner (+suspension)
├── survival/SurvivalMonitor, Threat health/hunger/oxygen assessment seam
├── world/                           BlockLocator, BiomeSensor, DistanceSensor,
│                                    LightSensor, PositionAnchor, StructureDirectory
├── state/, item/, crafting/         collectors, registries/kits/acquisitions,
│                                    recipe+smelting resolvers, craft/smelt executors
└── integration/                     BaritoneIntegration (reflective mine/goto/
                                     explore/equip), VanillaPlacementExecutor,
                                     VanillaSurvivalMonitor, VanillaEquipment,
                                     VanillaStorage

dev.minecraftai.agent                framework-free goal lifecycle core
├── goal/                            AgentGoal, GetItemGoal, GetKitGoal,
│                                    ExploreGoal, AgentGoalManager
├── item/, world/, command/          registry models + CLI smoke-check harness
```

Baritone is intentionally isolated behind `BaritoneIntegration`; this project does not embed or modify Baritone or Meteor Client code.
