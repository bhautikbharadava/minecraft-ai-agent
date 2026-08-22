# Minecraft AI Agent

A Fabric mod foundation for a future autonomous Minecraft AI agent.

## Current milestone

Implemented foundation only:

- Fabric/Gradle project structure.
- Main mod entry point: `com.bhautik.mcagent.McAgent`.
- `/agent status` command.
- World-state collection for player health, hunger, position, and dimension.
- Inventory-state collection with item-count summaries.
- Minimal planner, executor, goal, and Baritone integration seams.

Not implemented yet: autonomous mining, crafting, LLM integration, farms, or Baritone control.

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
```

## Architecture

```text
com.bhautik.mcagent
├── McAgent
├── command/AgentCommand
├── state/WorldState
├── state/WorldStateCollector
├── state/InventoryState
├── goal/Goal
├── planner/Planner
├── executor/AgentExecutor
└── integration/BaritoneIntegration
```

Baritone is intentionally isolated behind `BaritoneIntegration`; this project does not embed or modify Baritone or Meteor Client code.
