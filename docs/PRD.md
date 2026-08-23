# Minecraft AI Agent

## Product Requirements Document (PRD) with Use Cases

**Version:** 1.0\
**Repository:** `minecraft-ai-agent`\
**Primary stack:** Minecraft + Fabric + Fabric API + Baritone + custom
autonomous agent layer. Meteor may be used as an optional helper but is
not a core dependency.

------------------------------------------------------------------------

## 1. Product Vision

Build an autonomous Minecraft agent that receives high-level goals,
observes the world, creates and updates plans, executes actions through
Baritone and Minecraft APIs, verifies outcomes, and recovers from
failures.

The long-term experience should feel like giving a competent player an
objective rather than issuing low-level automation commands.

``` text
User:
"Get me a netherite pickaxe and full netherite armor."

Agent:
Understand goal
→ inspect current world and inventory
→ calculate requirements
→ decompose dependencies
→ choose strategies
→ execute actions
→ verify every stage
→ recover/replan when necessary
→ report success or failure
```

------------------------------------------------------------------------

## 2. Core Product Principles

-   **The agent owns decisions.** Baritone is an execution and
    pathfinding backend, not the brain.
-   **Deterministic first.** Do not require an LLM for normal planning
    or moment-to-moment decisions.
-   **Verify reality.** Do not blindly trust Baritone or an action's
    completion callback; verify against world state.
-   **Plan hierarchically.** High-level goals become subgoals,
    dependencies, and executable actions.
-   **Recover and replan.** The agent should adapt when an action fails
    or the world changes.
-   **Keep boundaries clean.** Goals, planning, actions, execution,
    state collection, and integrations must remain separate.

------------------------------------------------------------------------

## 3. System Architecture

``` text
User Command / Natural Language
            |
            v
      Goal Interpreter
            |
            v
       Goal Manager
            |
            v
          Planner
            |
            v
   Strategy / Decision Layer
            |
            v
      Action Scheduler
            |
            v
       Action Executor
        /          \
       v            v
Baritone Adapter   Minecraft Adapter
       |            |
       v            v
Navigation        Crafting
Mining            Smelting
Exploration       Placement
Pathfinding       Interaction
       \            /
        v          v
         Minecraft World
                |
                v
         World State Collector
                |
                └──── feedback to agent
```

------------------------------------------------------------------------

## 4. Responsibilities

### 4.1 Custom Agent Brain

The custom agent is responsible for:

-   Observing world state
-   Managing goals
-   Decomposing requirements
-   Creating plans
-   Selecting strategies
-   Prioritizing safety
-   Monitoring execution
-   Verifying results
-   Recovering and replanning

### 4.2 Baritone

Baritone is responsible for:

-   Pathfinding
-   Navigation
-   Mining
-   Exploration
-   Low-level movement
-   Obstacle handling where supported

The agent decides **what** and **why**.

Baritone helps execute **how to move through the world**.

### 4.3 Minecraft/Fabric APIs

Used for:

-   Inventory inspection
-   Recipe lookup
-   Crafting interactions
-   Smelting interactions
-   Block state inspection
-   Entity interactions
-   Container interaction
-   World state access

### 4.4 Meteor

Meteor is optional.

Meteor may be used as a development or helper integration, but the core
architecture must not depend on Meteor.

The primary product should work with:

``` text
Minecraft
+ Fabric
+ Baritone
+ Minecraft AI Agent
```

------------------------------------------------------------------------

# 5. Primary MVP

The first major capability:

``` text
/agent get <item> <count>
```

Examples:

``` text
/agent get cobblestone 64
/agent get oak_log 32
/agent get iron_ingot 10
/agent get iron_pickaxe 1
```

The MVP evolves from simple direct acquisition to recursive dependency
planning.

------------------------------------------------------------------------

# 6. Agent Lifecycle

``` text
OBSERVE
  ↓
UPDATE WORLD STATE
  ↓
CHECK EMERGENCY / SURVIVAL PRIORITY
  ↓
CHECK ACTIVE GOAL
  ↓
PLAN OR REPLAN IF REQUIRED
  ↓
EXECUTE CURRENT ACTION
  ↓
VERIFY RESULT
  ↓
RECOVER IF FAILED
  ↓
REPEAT
```

Do not run expensive planning every Minecraft tick.

Prefer:

-   Event-driven updates
-   State-change-triggered replanning
-   Periodic lightweight monitoring

------------------------------------------------------------------------

# 7. World State

The agent should operate on centralized world-state snapshots.

Minimum state:

## Player

-   Health
-   Maximum health
-   Hunger
-   Saturation
-   Armor
-   Position
-   Dimension

## Inventory

-   Item counts
-   Empty slots
-   Inventory full state
-   Equipped items
-   Held items

## World

-   Day/night
-   Biome
-   Nearby hostile entities
-   Nearby passive entities
-   Danger indicators

## Execution

-   Current goal
-   Current action
-   Execution status
-   Baritone status where available

Example conceptual structure:

``` text
Minecraft
    ↓
WorldStateCollector
    ↓
WorldState
    ↓
Planner / Goal / Actions
```

Tasks should avoid querying Minecraft APIs everywhere.

------------------------------------------------------------------------

# 8. Goal Model

Goal lifecycle:

``` text
PENDING
   ↓
ACTIVE
   ├── SUCCESS
   ├── FAILED
   └── CANCELLED
```

Every goal should provide:

-   Description
-   Status
-   Progress
-   Satisfaction check
-   Start behavior
-   Tick/update behavior
-   Cancellation behavior

Example:

``` text
GetItemGoal
├── target item
├── target amount
├── current amount
├── missing amount
└── status
```

------------------------------------------------------------------------

# 9. Get Item Goal

The primary goal is:

``` text
GetItemGoal(item, amount)
```

Example:

``` text
GetItemGoal(COBBLESTONE, 64)
```

The agent calculates:

``` text
Target: cobblestone
Requested: 64
Current: 12
Missing: 52
```

If:

``` text
Current >= Requested
```

then the goal immediately succeeds.

Otherwise:

``` text
Goal
  ↓
Planner
  ↓
Plan actions
  ↓
Execute
  ↓
Verify
```

------------------------------------------------------------------------

# 10. Planning and Dependency Resolution

The planner converts:

``` text
GOAL
```

into:

``` text
PLAN
```

Example:

``` text
Get iron pickaxe
```

Dependency tree:

``` text
iron_pickaxe × 1
├── iron_ingot × 3
│   └── acquire iron
└── stick × 2
    └── log/planks → sticks
```

Generic algorithm:

``` text
resolve(item, amount)

1. Count item already available.

2. If enough:
   requirement satisfied.

3. Else if craftable:
   resolve recipe ingredients.

4. Else if smeltable:
   resolve source and fuel.

5. Else if directly obtainable:
   create acquisition action.

6. Otherwise:
   fail with unsupported acquisition strategy.
```

The planner should always account for existing inventory before
gathering new resources.

------------------------------------------------------------------------

# 11. Action Model

All executable actions should follow a common lifecycle:

``` text
PENDING
  ↓
RUNNING
  ├── SUCCESS
  ├── FAILED
  └── CANCELLED
```

Initial actions:

-   `MoveAction`
-   `MineAction`
-   `GatherAction`
-   `CraftAction`
-   `SmeltAction`
-   `ExploreAction`

Future actions:

-   `PlaceBlockAction`
-   `BreakBlockAction`
-   `InteractAction`
-   `AttackAction`
-   `UseItemAction`

------------------------------------------------------------------------

# 12. Baritone Integration

Baritone must be isolated behind a dedicated integration layer.

Architecture:

``` text
GetItemGoal
    ↓
Planner
    ↓
MineAction / MoveAction / ExploreAction
    ↓
ActionExecutor
    ↓
BaritoneIntegration
    ↓
Baritone
```

Rules:

-   Goals must not call Baritone directly.
-   Planners must not call Baritone directly.
-   Only executor/integration layers communicate with Baritone.
-   Avoid chat-command automation.
-   Use supported Java APIs where available.
-   Support cancellation.
-   Support execution status.
-   Report failures to the agent.

Baritone is the low-level execution backend.

------------------------------------------------------------------------

# 13. Verification

Every important operation must be independently verified.

## Mining

Verify actual inventory count.

``` text
Requested: 64 cobblestone
Inventory: 64 cobblestone
→ SUCCESS
```

## Crafting

Verify output item exists.

## Smelting

Verify produced output exists.

## Movement

Verify position or distance.

## Block placement

Verify actual block state.

## Building

Verify the structure.

Never blindly trust an executor's completion signal.

------------------------------------------------------------------------

# 14. Recovery and Replanning

Recovery pipeline:

``` text
Action fails
    ↓
Observe current state
    ↓
Is goal already satisfied?
    ├── Yes → SUCCESS
    └── No
         ↓
       Retry?
         ├── Yes → Retry
         └── No
              ↓
      Alternative strategy?
         ├── Yes → Replan
         └── No → FAIL
```

Recovery levels:

1.  Retry.
2.  Adjust execution parameters.
3.  Change strategy.
4.  Replan the goal.
5.  Fail safely with a useful reason.

------------------------------------------------------------------------

# 15. Priority and Survival

Priority:

``` text
EMERGENCY
   ↓
SURVIVAL
   ↓
ACTIVE USER GOAL
   ↓
BACKGROUND TASKS
```

Example:

``` text
Mining iron
    ↓
Health becomes critical
    ↓
Suspend mining
    ↓
Recover / escape
    ↓
Safe?
    ├── Yes → Resume or replan
    └── No → Continue recovery
```

Initial survival rules:

``` text
IF health is critical
    stop dangerous activity

IF hunger is too low
    eat if possible

IF taking sustained damage
    escape

IF current execution is impossible
    recover or replan
```

------------------------------------------------------------------------

# 16. Use Cases

## UC-01: Get Cobblestone

User:

``` text
/agent get cobblestone 64
```

Current inventory:

``` text
cobblestone × 12
```

Plan:

``` text
Missing: 52
    ↓
MineAction(cobblestone, 52)
    ↓
BaritoneIntegration
    ↓
Baritone mines
    ↓
Monitor inventory
    ↓
Inventory >= 64?
    ↓
SUCCESS
```

------------------------------------------------------------------------

## UC-02: Already Have the Requested Item

User:

``` text
/agent get oak_log 16
```

Inventory:

``` text
oak_log × 20
```

The agent:

``` text
Check inventory
    ↓
Already satisfied
    ↓
SUCCESS
```

No unnecessary action is executed.

------------------------------------------------------------------------

## UC-03: Invalid Request

User:

``` text
/agent get not_a_real_item 10
```

The item registry lookup fails.

Result:

``` text
Invalid item
No goal created
```

Invalid count:

``` text
/agent get cobblestone -5
```

Result:

``` text
Invalid count
No goal created
```

------------------------------------------------------------------------

## UC-04: Cancel an Active Goal

User:

``` text
/agent cancel
```

Agent:

``` text
Active goal
    ↓
Cancel current action
    ↓
Stop Baritone if owned by agent
    ↓
Release executor state
    ↓
Goal = CANCELLED
    ↓
Agent = IDLE
```

------------------------------------------------------------------------

## UC-05: Get an Iron Pickaxe

User:

``` text
/agent get iron_pickaxe 1
```

Agent:

``` text
Check inventory
    ↓
Already have iron pickaxe?
    ├── Yes → SUCCESS
    └── No
         ↓
       Resolve recipe
         ↓
       iron_ingot × 3
       stick × 2
         ↓
       Acquire missing resources
         ↓
       Craft ingredients
         ↓
       Craft iron pickaxe
         ↓
       Verify
```

------------------------------------------------------------------------

## UC-06: Smelting Iron

If the agent needs iron ingots:

``` text
Need iron ingots
    ↓
Need iron-bearing input
    ↓
Need furnace
    ↓
Need fuel
    ↓
Start smelting
    ↓
Monitor
    ↓
Verify output
```

The system must not block the game thread while waiting.

------------------------------------------------------------------------

## UC-07: Survival Interruption

While mining:

``` text
MineAction RUNNING
    ↓
Danger detected
    ↓
Suspend action
    ↓
Recovery behavior
    ↓
Safe?
    ├── Yes → Resume/replan
    └── No → Continue recovery
```

------------------------------------------------------------------------

## UC-08: Find a Biome

User:

``` text
/agent explore desert
```

Agent:

``` text
Goal: find desert biome
    ↓
Check current biome
    ↓
Already desert?
    ├── Yes → SUCCESS
    └── No
         ↓
       Explore strategy
         ↓
       Baritone navigation
         ↓
       Verify biome
```

------------------------------------------------------------------------

## UC-09: Multi-Item Goal

User:

> Give me an iron sword, shield, and full iron armor.

The agent creates a composite goal:

``` text
EquipmentGoal
├── iron_sword
├── shield
├── iron_helmet
├── iron_chestplate
├── iron_leggings
└── iron_boots
```

Shared dependencies must be deduplicated.

The agent should calculate total iron requirements instead of
independently gathering resources for each item.

------------------------------------------------------------------------

## UC-10: Netherite Pickaxe and Full Netherite Armor

User:

> Get me a netherite pickaxe and full netherite armor.

Composite goal:

``` text
├── netherite_pickaxe × 1
├── netherite_helmet × 1
├── netherite_chestplate × 1
├── netherite_leggings × 1
└── netherite_boots × 1
```

Pipeline:

``` text
Calculate shared requirements
    ↓
Check existing inventory/equipment
    ↓
Acquire diamond equipment
    ↓
Acquire netherite-related materials
    ↓
Obtain upgrade templates
    ↓
Enter / navigate Nether when required
    ↓
Mine ancient debris
    ↓
Smelt → netherite scraps
    ↓
Combine with gold → netherite ingots
    ↓
Use smithing workflow
    ↓
Verify all final items
    ↓
SUCCESS
```

This is a long-term benchmark, not an MVP task.

It requires:

-   Composite goals
-   Shared dependency planning
-   Progression planning
-   Nether navigation
-   Safe exploration
-   Ancient debris mining
-   Template acquisition
-   Smelting
-   Smithing
-   Recovery and replanning

------------------------------------------------------------------------

## UC-11: Build a Known Farm

User:

> Build me an iron farm.

Agent:

``` text
Select compatible blueprint
    ↓
Validate location
    ↓
Calculate materials
    ↓
Acquire missing materials
    ↓
Prepare area
    ↓
Place blocks
    ↓
Configure entities/redstone
    ↓
Verify farm output
```

Early versions should use known compatible blueprints rather than
inventing farm designs.

------------------------------------------------------------------------

# 17. Milestone Roadmap

## M1 --- Foundation

-   Fabric project
-   Main entry point
-   `/agent status`
-   World state
-   Inventory state
-   README
-   Buildable project

## M2 --- Goal Lifecycle

Commands:

``` text
/agent get <item> <count>
/agent goal
/agent cancel
```

No autonomous execution yet.

## M3 --- First Execution

Support:

``` text
/agent get cobblestone 64
```

Pipeline:

``` text
GetItemGoal
    ↓
MineAction
    ↓
BaritoneIntegration
    ↓
Inventory monitoring
    ↓
SUCCESS
```

## M4 --- General Direct Acquisition

Support more directly:

-   Mineable items
-   Gatherable items
-   Collectable resources

## M5 --- Dependency Planning

Enable:

``` text
/agent get iron_pickaxe 1
```

with recursive requirements.

## M6 --- Crafting

Acquire ingredients, craft intermediates, craft final item, verify.

## M7 --- Smelting

Support:

-   Furnace
-   Fuel
-   Inputs
-   Outputs
-   Verification

## M8 --- Survival Interruptions

Support:

``` text
Goal execution
    ↓
Danger
    ↓
Suspend
    ↓
Recover
    ↓
Resume
```

## M9 --- Exploration

Support:

-   Find resources
-   Find biomes
-   Find structures
-   Explore caves

## M10 --- Building Foundation

Introduce:

-   `PlaceBlockAction`
-   `BreakBlockAction`
-   Structure verification

## M11 --- Farm Building

Support:

``` text
Select blueprint
    ↓
Acquire materials
    ↓
Build
    ↓
Verify
```

## M12 --- Natural Language Layer

Optional LLM/parser:

``` text
Natural language
    ↓
LLM or parser
    ↓
Structured goal
    ↓
Deterministic agent
```

------------------------------------------------------------------------

# 18. LLM Strategy

The default system should be deterministic.

Do not use an LLM for:

-   Every game tick
-   Movement
-   Basic inventory checks
-   Recipe resolution
-   Repetitive decisions

Potential LLM use:

``` text
Natural language
    ↓
Optional LLM
    ↓
Structured goal
    ↓
Deterministic planner
    ↓
Action execution
```

Example:

``` text
User:
"Build a safe starter base near water."

LLM/parser:
{
  goal: BUILD_STRUCTURE,
  structure: STARTER_BASE,
  constraints: {
    near_water: true,
    safe: true
  }
}
```

The deterministic agent handles execution.

------------------------------------------------------------------------

# 19. Quality Requirements

The system must:

-   Be modular
-   Be buildable after every milestone
-   Avoid unnecessary dependencies
-   Avoid blocking the main Minecraft thread
-   Support safe cancellation
-   Independently verify execution
-   Log meaningful lifecycle events
-   Recover from failures
-   Avoid direct Baritone dependencies in goals/planners
-   Keep clear boundaries between:
    -   State
    -   Goals
    -   Planning
    -   Actions
    -   Execution
    -   Integrations

------------------------------------------------------------------------

# 20. Testing Requirements

Test where practical:

## Goals

-   Already satisfied
-   Missing resources
-   Invalid items
-   Invalid counts
-   Cancellation
-   Success

## Planning

-   Simple dependencies
-   Recursive dependencies
-   Shared dependencies
-   Unsupported acquisition

## Actions

-   Success
-   Failure
-   Cancellation
-   Recovery

Mock integrations where appropriate.

------------------------------------------------------------------------

# 21. Logging

Log major transitions:

``` text
[Agent] Goal created
[Agent] Goal started
[Planner] Plan generated
[Action] Started
[Action] Verified success
[Recovery] Action failed
[Recovery] Retrying
[Agent] Goal completed
```

Do not log every game tick.

------------------------------------------------------------------------

# 22. OpenCode Instructions

Before implementing:

1.  Inspect the repository.
2.  Inspect the existing architecture.
3.  Determine the highest completed milestone.
4.  Do not recreate completed work.
5.  Identify the smallest next milestone.
6.  Implement only that milestone.
7.  Build and test.
8.  Review the diff.
9.  Commit the completed milestone.

At the end report:

-   Files changed
-   Key design decisions
-   Build/test results
-   Known limitations
-   Recommended next milestone

## Important

Do not implement the entire PRD in one task.

The project must evolve through small, working milestones.

------------------------------------------------------------------------

# 23. Immediate Execution Instruction

Inspect the repository and determine the highest completed milestone.

If goal lifecycle is complete, implement the smallest next executable
milestone:

``` text
/agent get cobblestone 64
```

Pipeline:

``` text
GetItemGoal
    ↓
Planner
    ↓
MineAction
    ↓
ActionExecutor
    ↓
BaritoneIntegration
    ↓
Baritone
    ↓
Inventory verification
    ↓
SUCCESS
```

Requirements:

-   Support safe cancellation.
-   Keep Baritone behind the integration layer.
-   Verify actual inventory independently.
-   Do not jump ahead to crafting.
-   Do not jump ahead to Netherite.
-   Do not add LLM integration.
-   Do not implement farm building yet.

After completion:

-   Build the project.
-   Test the implementation.
-   Review the diff.
-   Commit the milestone.
-   Stop and report results.
