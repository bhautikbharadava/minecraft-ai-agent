# Agent instructions for this repo

Read this before working. It records how work gets verified here, not
just how it gets written.

## Definition of done: verify in the real game

**A task is not finished when it compiles. It is finished when it has
been run in the client and the logs are clean.**

After finishing any task, always do all four, in order:

1. **Build** — `gradle build` (see JAVA_HOME below). This also runs the
   JVM smoke checks.
2. **Relaunch the client** — `gradle runClient`. Kill any client still
   running the old build first; a stale client silently tests old code.
3. **Give the user the exact `/agent ...` commands to try**, in the
   order they should be run, including any world setup they need
   (spawning near cows, standing in a cave, etc.). Do not make the user
   work out how to exercise the change.
4. **Watch the client logs for errors** while it runs — read the launch
   output and keep checking it. Report anything that appears:
   exceptions, stack traces, `[Recovery] Action failed`, Baritone
   warnings, missing-registry errors. Say plainly when the logs are
   clean; never claim a feature works without having looked.

If the client cannot be launched or the feature cannot be exercised, say
so explicitly rather than implying it was verified.

## Build

`java` is not on PATH on this machine and there is no JDK in
`/Library/Java/JavaVirtualMachines`. Gradle needs JDK 25 pointed at
explicitly:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home PATH="$JAVA_HOME/bin:$PATH" gradle build --offline
```

Use the same prefix for `runClient`. `--offline` avoids re-resolving
dependencies on every run.

## Testing convention

There are no unit tests. The gate is **JVM smoke checks**: deterministic
scenario validation in `dev.minecraftai.agent.AgentCli` (`validate`),
run automatically by `gradle build`, with faked seams and no game
instance. Add a `validateX()` for new planner/goal behavior and register
it in `main`. Note `assertEquals` compares by reference — use
`assertIntEquals` for numbers.

### Every long-running action needs a stall scenario

`validateNoStalls` drives each tick-based action against seams that
refuse everything and asserts it either reaches a terminal state or
changes something observable. **Add a scenario for any new action that
runs across ticks.**

This is the project's most expensive bug class, by a wide margin. Every
instance had the same shape: a per-tick operation that can fail forever,
with the give-up clock reset by the failure loop itself. Hunting
restarted its roam every tick so the timeout never advanced; a stash
that freed nothing re-triggered three times a second; obsidian flapped
between SEEKING and POURING; farming looped TILLING → SOWING → GROWING →
TILLING. None of them needed a running game to catch — only this
assertion, which none of them survive.

Two rules that follow from it:

- **A phase/flag change that resets a timeout must be counted.** If two
  states can hand off to each other, cap the number of switches, and
  keep a total budget that no transition resets.
- **A failing operation needs a way to conclude the target is
  impossible**, not just a retry limit — rejecting one buried lava
  source at a time never helped, because every block of a covered lake
  is buried.

The harness self-checks: a deliberately spinning action must be caught,
and the build fails if it is not. A harness that cannot fail is worse
than none, because it reads as evidence while checking nothing.

## Architecture rules

- **Framework-free cores + seam adapters.** Planner/goal/action logic
  must not import Minecraft classes. Every world touch goes through an
  interface (`BlockLocator`, `XpSensor`, `Hunter`, `Crafter`, ...)
  implemented in `com.bhautik.mcagent.integration` and faked in smoke
  checks.
- **Baritone stays behind `BaritoneIntegration`** (reflective, optional
  at runtime). Only actions touch it — never goals or planners.
- **Honest failure over pretend-work.** A goal that cannot proceed fails
  with a specific reason ("no cows within 48 blocks"). Never report
  success for a no-op.
- **Verify against live world state**, never an action's own claim.
  Actions judge themselves by real inventory counts / block state.
- **Server-authoritative mutation.** Mutate the integrated server's
  copy (inventory, XP); vanilla syncs the client. Client-side mutation
  desyncs drops.
- **Gather-before-place**: all mining steps precede utility-block
  placement, or Baritone digs through freshly placed blocks.

## Docs to keep current

- `docs/PROGRESS.md` — milestone status, limitations, backlog. Update
  when anything lands or changes scope.
- `docs/PRD.md` — requirements source, treat as given.
- `README.md` — commands and architecture summary.
- Feature design docs (e.g. `docs/ENCHANTING.md`) — update slice status
  as slices land.

Record real limitations in "Known limitations" rather than quietly
leaving them out.
