# Enchanting — Design Doc

Backlog item #8 (see [PROGRESS.md](PROGRESS.md)). Effort: **Large**.
Status: **design only — no code yet**. `feat/enchanting` currently equals
`master` (zero enchant references in `src/`).

This doc is the plan of record for enchanting and the two self-sourcing
subsystems it pulls in (leather, XP). It follows the repo conventions:
framework-free cores in `dev.minecraftai.agent`, every world touch behind
a seam implemented in `com.bhautik.mcagent.integration`, honest failure
over pretend-work, and JVM smoke checks (`AgentCli validate`) as the test
gate.

---

## 1. Goal

Ship `/agent enchant <item> [level]`: the agent self-provisions an
enchanting table (optionally a bookshelf ring), farms the XP it needs,
sources leather for the book chain, then drives the real vanilla
enchanting menu to enchant a held tool/armor/book — verified against live
`ItemStack` enchantments, failing honestly when a prerequisite can't be
met.

Per the user's scope decisions:

- **Leather** is self-sourced (cow hunting primary; villager trading as a
  documented alternative) — not required from the player.
- **XP** is self-farmed to a target level before enchanting.

---

## 2. Vanilla mechanics reference

- **Enchant cost**: an enchanting table offers 3 options; each consumes
  **1–3 lapis lazuli** and **XP levels** up to the option's cost. The top
  option needs the agent to be at least that many levels.
- **Power (bookshelves)**: up to **15 bookshelves** placed one block away
  from the table (same Y and Y+1, in the vanilla ring pattern) raise the
  max offered level toward **30**. Zero bookshelves caps offers at ~1–8.
- **The offer roll** (`EnchantmentMenu`) is seeded per player and
  recomputed from bookshelf count + item enchantability. Driving the real
  menu server-side reproduces vanilla exactly (seed, cost, enchant set) —
  preferred over re-implementing the roll.
- **Recipes** (leaf → target):
  - `paper` = 3 × `sugar_cane`
  - `book` = 3 × `paper` + 1 × `leather`
  - `bookshelf` = 6 × `planks` + 3 × `book`
  - `enchanting_table` = 1 × `book` + 2 × `diamond` + 4 × `obsidian`
- **XP sources** the agent can already reach: mining XP-bearing ores
  (`coal`, `redstone`, `lapis_lazuli`, `diamond`, `emerald`,
  `nether_quartz`) and killing mobs (combat v0). Smelting also grants XP
  on output collection.

---

## 3. Dependency graph

```text
/agent enchant <item> [level]
   │
   ├─ enchanting table nearby ──no──► craft+place table
   │                                     └─ needs: 1 book, 2 diamond, 4 obsidian
   │                                                   └─ book needs leather ─┐
   ├─ (optional) bookshelf ring for level ──► 15 bookshelves                  │
   │                                            └─ 45 book = 45 leather ──────┤
   ├─ lapis lazuli ≥ 1 ──no──► mine lapis_ore (STONE tier)                    │
   ├─ XP level ≥ cost ──no──► XP FARM (mine XP ores until level target)       │
   └─ LEATHER ──────────────────────────────────────────────────────────────┘
                └─ cow hunting (primary) | villager trading (alternative)
```

Leather is the spine: both the table's book and every bookshelf need it,
so **Subsystem B (leather) gates Subsystem C (table/shelves)**. XP
(Subsystem A) is independent and can be built first.

---

## 4. Subsystems

### A. XP sensing + farming

**What exists:** nothing reads XP anywhere in `src/`. The only player
imported is `ServerPlayer`; stat reads live in `VanillaSurvivalMonitor`
and `WorldStateCollector`.

**Sensing seam** (framework-free, `com.bhautik.mcagent.survival` or a new
`world` sensor):

```java
public interface XpSensor {
    int level();        // ServerPlayer.experienceLevel
    int totalPoints();  // ServerPlayer.totalExperience
}
```

`VanillaXpSensor` reads the public `ServerPlayer` fields. Surface XP level
in `WorldStateCollector`/`WorldState` and `/agent status`.

**Farming:** all XP-bearing ores are already direct acquisitions
(`coal` WOOD `DirectAcquisitions.java:80`, `redstone` IRON `:84`,
`lapis_lazuli` STONE `:83`, `diamond`/`emerald` IRON `:85/:86`,
`quartz` WOOD `:93`). An XP-farm behavior loops the existing planner to
mine batches of the cheapest reachable XP ore, walking over dropped orbs
(vanilla auto-pickup), re-checking `XpSensor.level()` each cycle until
`level ≥ target` or an honest cap/timeout. Preference order by tool tier:
`coal → redstone → lapis → diamond`. Reaching level 30 is ~1395 XP
(hundreds of ores) — the doc's honest-failure section covers the cap.

Reuses: `Planner.planAcquisition` (single-root), `MineAction`,
`DirectAcquisitions`. No new mining code — only the loop + level check.

### B. Leather sourcing

Leather is a mob drop, absent from `DirectAcquisitions`. Two paths; **cow
hunting is the recommended primary** (deterministic, reuses the combat
seam, synergizes with the food-economy backlog #4 via dropped beef).

**B1. Cow hunting (primary).** Generalize the combat scan from
`VanillaCombat` (which does `level.getEntitiesOfClass(Monster.class, box)`
→ face → `player.attack(target)` → respect `getAttackStrengthScale`) into
a passive-animal hunter:

```java
public interface CattleHunter {
    boolean strikeNearestCow(double range);  // returns engaged?
}
```

`VanillaCattleHunter` scans `net.minecraft.world.entity.animal.Cow`
instead of `Monster`, otherwise identical. A new acquisition strategy
maps `leather` → "hunt cows within radius": walk toward the nearest cow
(`MoveAction`), strike until dead, collect drops, repeat until the leather
count target is met or no cows remain in range (honest failure: "no cows
within N blocks"). Optional later refinement: breed cows with wheat for a
renewable supply (adds wheat farming — out of first scope).

**B2. Villager trading (alternative, documented not built first).**
Librarian villagers sell **enchanted books** directly (shortcut past the
table for specific enchants, applied via an anvil); leatherworkers trade
leather. Requires: locating a villager of the right profession, emeralds
(mineable — `emerald` IRON `:86`), and driving `MerchantMenu`
server-side. Heavier (GUI + profession/trade-tier RNG + anvil subsystem),
so it is recorded as a future extension rather than the first path.

**Decision surfaced:** first build ships **cow hunting**; villager
trading is a follow-up milestone.

### C. Enchanting table & bookshelf provisioning

**Table access — copy the crafting-table model exactly.** The planner
already walks-to / places / collects utility blocks via
`Planner.planBlockAccess(blockItemId, locator)` (`:337–362`), wired for
the crafting table (`:321–328`) and furnace (`:286–290`), with a
collection `BreakBlockAction` appended for every block the plan placed
(`:180–186`). To add the enchanting table:

1. New constant `ENCHANTING_TABLE_ITEM = "minecraft:enchanting_table"` in
   `Planner`.
2. Extend the `Environment` record (`Planner.java:84–96`, currently 12
   components) with a **13th** field `BlockLocator enchantTableLocator`.
   Build it in `GoalService.environmentFor` (`GoalService.java:1027`) via
   the generic `VanillaPlacementExecutor.blockLocator(player,
   ENCHANTING_TABLE_ITEM, INTERACTION_RADIUS)` (`:41`) — the locator is
   already block-item-id generic, no new adapter needed.
3. Add the enchanting-table branch to the placed-block collection switch
   (`Planner.java:181–182`) so a placed table is broken and re-collected
   like the crafting table.

The table itself, `book`, `paper`, and `bookshelf` all resolve
automatically through `VanillaRecipeResolver` (it indexes every vanilla
crafting recipe up to 3×3); their leaves bottom out at `sugar_cane`
(HAND), `obsidian` (DIAMOND), `diamond` (IRON) — all already mineable —
plus `leather` from Subsystem B. `planks`/`diamond_pickaxe` tool ladders
already work.

**Bookshelf ring (Slice 4, optional for level 30).** Placing 15
bookshelves in the vanilla pattern (a ring one block out from the table,
at table Y and Y+1, with air between shelf and table) is new
multi-placement logic — a small ring planner emitting 15 `PlaceBlockAction`
steps at computed offsets. First release can enchant with **0–few**
bookshelves (lower levels) and treat the full ring as a later refinement.

### D. Enchant interaction

New action mirroring `CraftAction`/`SmeltAction`:

```java
// action/EnchantAction.java
public interface Enchanter {
    Result enchant(String itemId, int minLevel);  // Result(success, failureReason)
}
```

`VanillaEnchanter` (integration):

1. Locate the nearby enchanting table (via the new locator).
2. Build `EnchantmentMenu` with
   `ContainerLevelAccess.create(level, tablePos)`; place the target item
   in the enchant input slot and lapis in the lapis slot.
3. Read the 3 vanilla-computed offers (`menu.costs` / clue slots).
4. Pick the highest offer whose level cost `≤ player.experienceLevel`
   and `≥ minLevel` (if given); if none affordable, fail honestly
   ("need level N, have M").
5. `menu.clickMenuButton(player, chosenSlot)` — vanilla consumes lapis +
   XP and applies the enchant set.
6. Verify `itemStack.getEnchantments()` is non-empty (or contains the
   requested enchant); return success/failure accordingly.

Fallback if driving the live menu proves fragile:
`EnchantmentHelper.enchantItem(RandomSource, stack, level, holderLookup,
tagOpt)` applies a vanilla-equivalent random set for a level cost, with
lapis/XP consumed manually. Same honest costs, less faithful RNG.

### E. Enchant goal + command

- **`EnchantGoal`** (framework-free, modeled on `ExploreGoal` —
  `ExploreGoal.java:16`, a single `BooleanSupplier` completion): satisfied
  when the target held item carries ≥1 enchantment (or the requested
  one). `progressReport()` prints target item + enchant state + status.
- **Wire into `GoalService`**: new `enchant(player, item, level)` entry +
  a new `ActiveRun` branch (peer to the kit/explore/stash/restock paths at
  `GoalService.java:152/174/247/297/403/458/488`). The plan orders:
  `[secure leather] → [craft+place table] → [(optional) bookshelf ring]
  → [mine lapis] → [XP farm to cost] → [enchant] → [collect table]`.
- **Command**: add `/agent enchant <item> [level]` to the Brigadier tree
  in `AgentCommand.java:28` (peer to `get`/`explore`), plus a
  `goalService.isEnchantable(item)` validation and usage-line update
  (`:84`).

---

## 5. Seam & file-change map

| # | File | Change |
|---|---|---|
| 1 | `world/XpSensor.java` (new) | `level()`, `totalPoints()` seam |
| 2 | `integration/VanillaXpSensor.java` (new) | reads `ServerPlayer` XP |
| 3 | `state/WorldState.java`, `state/WorldStateCollector.java` | add XP level field + `/agent status` line |
| 4 | `action/CattleHunter.java` (new) | `strikeNearestCow(range)` seam |
| 5 | `integration/VanillaCattleHunter.java` (new) | `Cow` scan, mirrors `VanillaCombat` |
| 6 | `item/DirectAcquisitions.java:49` | `leather` → hunt-cows strategy (+ new strategy enum/branch) |
| 7 | `planner/Planner.java:52` | `ENCHANTING_TABLE_ITEM` constant |
| 8 | `planner/Planner.java:84` | `Environment` +`enchantTableLocator` (13th field) |
| 9 | `planner/Planner.java:181` | collect placed enchanting table |
| 10 | `action/EnchantAction.java` (new) | action + `Enchanter` seam |
| 11 | `crafting/VanillaEnchanter.java` (new) | drives `EnchantmentMenu` |
| 12 | `dev/minecraftai/agent/goal/EnchantGoal.java` (new) | goal (ExploreGoal shape) |
| 13 | `goal/GoalService.java` | `enchant(...)` + `ActiveRun` branch + `environmentFor` locator + XP-farm loop |
| 14 | `command/AgentCommand.java:28` | `/agent enchant` subcommand + usage |
| 15 | `dev/minecraftai/agent/command/AgentCli.java` | new smoke-check scenarios |
| 16 | (Slice 4) `planner` ring helper | 15-bookshelf placement |

No changes to Baritone integration boundaries; all navigation stays
behind existing `MoveAction`/`BaritoneIntegration`.

---

## 6. Milestone slices (build order, each green + smoke-checkable)

| Slice | Scope | Depends on | Verify |
|---|---|---|---|
| **E1** | XP sensing + `/agent status` XP + `/agent enchant` readiness report (no mutation) | — | smoke: readiness reasons for all-missing prereqs |
| **E2** ✅ | XP farming (`XpFarmAction`, coal ore, only when a level is requested) | E1 | build: level target + honest give-up |
| **E3** ✅ | Cow-hunting leather acquisition (`Hunter` seam + `MobDrops` + `HuntAction`) | — | smoke: `get leather N` plans one hunt; `book` chain hunts + crafts |
| **E4** | Table provisioning (constant + `Environment` field + `planBlockAccess` + collect) | E3 | smoke: `get enchanting_table 1` full chain from bare hands |
| **E5** | `EnchantAction` + `VanillaEnchanter` + `EnchantGoal` + command; end-to-end enchant at available level | E2, E4 | smoke: enchant plan ordering; in-game manual check |
| **E6** ✅ | Bookshelf ring as a blueprint (`enchanting_room`) | E5 | smoke: 15 shelves at radius 2, radius-1 gaps cleared |
| **E7** *(future)* | Villager trading alternative for leather / enchanted books | E5 | — |

Recommended: build **E1 → E3** first (both self-contained, no XP/menu
risk), review, then E2/E4/E5.

---

## 7. Honest-failure behaviors (PRD §14 — reality first)

- No cows in range → `leather` acquisition fails: "no cows within N
  blocks; move nearer a herd or supply leather/books."
- XP target unreachable within the ore cap → enchant fails: "reached
  level M/target N after mining cap; not enough XP to enchant."
- No lapis and none mineable nearby → fail with the mining reason.
- Table can't be placed (no `BlockItem` spot) → same failure path as the
  crafting table today.
- Item already enchanted / not enchantable → succeed-immediately or
  reject at command validation, never a no-op "success".
- Every enchant re-checks live `ItemStack` enchantments before declaring
  success (satisfaction-first recovery, matching existing goals).

---

## 8. Risks & open decisions

- **Leather volume for a full ring**: 15 bookshelves = 45 books = 45+
  leather ≈ 30–45 cow kills. First release should default to a **small
  shelf count** (or none) and treat the full ring as opt-in via the
  `[level]` argument, so a basic enchant isn't gated on a cattle massacre.
- **XP grind length**: level 30 is a long mine. Consider defaulting
  `/agent enchant <item>` to "best affordable now" and only farming XP up
  to an explicit `[level]`.
- **`EnchantmentMenu` server-side wiring** is the highest-uncertainty
  piece; the `EnchantmentHelper.enchantItem` fallback de-risks E5.
- **Villager trading** (E7) is deferred; if it lands, enchanted-book +
  anvil could bypass the table/shelf/XP path for targeted enchants — a
  materially different design worth its own doc.
- **Cow breeding** (renewable leather) is out of first scope; hunting
  existing herds only.

---

## 9. Docs to update when slices land

- `PROGRESS.md`: flip roadmap row 8, add per-slice rows, move enchanting
  out of "Known limitations", note leather/XP subsystems.
- `README.md`: `/agent enchant` in the command list; drop enchanting from
  the "Not yet" line.
