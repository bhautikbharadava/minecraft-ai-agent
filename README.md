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

Not implemented yet: crafting, smelting, survival interruptions,
exploration goals, LLM integration, or farms.

### Execution backend

Mining is delegated to [Baritone](https://github.com/cabaletta/baritone)
through a reflective integration layer: install Baritone alongside the mod
to enable autonomous mining. Without Baritone, goals fail fast with a
clear reason instead of pretending to work. Goals and planners never call
Baritone directly — only actions touch the integration layer, which can be
swapped for any navigation backend.

## Commands

```text
/agent status                 show world/inventory snapshot and executor state
/agent get <item> <count>     register a GetItemGoal and start executing it
/agent goal                   report the active goal's live progress
/agent cancel                 cancel the active goal and stop mining
```

Items resolve by shorthand name (for example `cobblestone`). If the
inventory already satisfies the request, the goal succeeds immediately;
otherwise the agent plans mining, tracks inventory progress, retries once
on stall, and verifies the final count before reporting SUCCESS.

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
├── action/AgentAction, ActionStatus, MineAction
├── state/WorldState
├── state/WorldStateCollector
├── state/InventoryState
├── goal/GoalService        (agent brain: plan → execute → verify → recover)
├── goal/Goal               (planner-facing seam, pre-execution)
├── planner/Planner
├── executor/AgentExecutor  (single-action tick runner)
├── item/MineableItems      (directly-mineable item → source block map)
└── integration/BaritoneIntegration (reflective, swap-safe backend)

dev.minecraftai.agent         (framework-free goal lifecycle core)
├── goal/AgentGoal, GoalStatus, GetItemGoal, AgentGoalManager
├── item/MinecraftItem, ItemRegistry
├── world/InventoryState
└── command/AgentCommandHandler + AgentCli (JVM smoke checks)
```

Baritone is intentionally isolated behind `BaritoneIntegration`; this project does not embed or modify Baritone or Meteor Client code.
