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
- In-game commands: `/agent get <item> <count>`, `/agent goal`,
  `/agent cancel` (goal registration and tracking only).
- Minimal planner, executor, goal, and Baritone integration seams.

Not implemented yet: autonomous execution (mining via Baritone),
crafting, smelting, LLM integration, or farms.

## Commands

```text
/agent status                 show world/inventory snapshot and executor state
/agent get <item> <count>     register a GetItemGoal against current inventory
/agent goal                   report the active goal's progress
/agent cancel                 cancel the active goal
```

Items resolve by shorthand name (for example `cobblestone`) against a
small vanilla defaults list. If the inventory already satisfies the
request, the goal succeeds immediately; otherwise it stays `ACTIVE`
until execution lands in a later milestone.

## Versions

- Minecraft: `1.21.8`
- Yarn mappings: `1.21.8+build.1`
- Fabric Loader: `0.17.2`
- Fabric API: `0.133.3+1.21.8`
- Fabric Loom: `1.10.1`
- Java: `21`

## Build

```bash
gradle build
```

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
├── state/WorldState
├── state/WorldStateCollector
├── state/InventoryState
├── goal/GoalService        (adapts the goal lifecycle to live game state)
├── goal/Goal               (planner-facing seam, pre-execution)
├── planner/Planner
├── executor/AgentExecutor
└── integration/BaritoneIntegration

dev.minecraftai.agent         (framework-free goal lifecycle core)
├── goal/AgentGoal, GoalStatus, GetItemGoal, AgentGoalManager
├── item/MinecraftItem, ItemRegistry
├── world/InventoryState
└── command/AgentCommandHandler + AgentCli (JVM smoke checks)
```

Baritone is intentionally isolated behind `BaritoneIntegration`; this project does not embed or modify Baritone or Meteor Client code.
