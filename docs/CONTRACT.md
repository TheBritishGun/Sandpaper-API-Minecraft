# The Sandpaper contract

Rules a caller of Sandpaper must follow: 1 to 16 for the scheduler and the work pool, 17 to 19 for the settings screen, 20 to 25 for values work, and 26 to 35 for frame spread. Stop at 16 if you only schedule work.

---

## 1. The scheduler is not thread-safe

`Scheduler.pump` fixes which thread is pumping. Call `register`, `registerSliced`, `cancelAllFor`, `cancelAll`, `stats`, and a `Handle`'s counters and retimes, only from that thread; a call from elsewhere still runs, but logs an error once per method, naming the call site.

A new thread takes over pumping, silently, once the pumping thread has ended. Two threads pumping at once are not silent: the next checked call made off the recorded pumping thread logs it.

For work from any thread, use `WorkPool`: `submit` accepts work from any thread, and `drainCompleted` applies results back on one drain thread, captured and handed over the same way. A consumer needing scheduled work off the game thread runs its own `Scheduler`, pumped by its own thread.

## 2. Priority orders jobs; it does not run them past budget

`Priority` is `LOW`, `NORMAL` or `HIGH`, and only orders deferrable jobs against each other. `mustRun` runs a job every period regardless of budget; combine it with `Priority.LOW` for cheap work that must not be late.

## 3. A deferred job's elapsed time can be large

`Tick.sinceLastRunNanos()` may report a long gap when the budget skipped a run; that is not a fault. Integrate against it, never against a per-frame delta.

## 4. Clamp elapsed time before integrating it

A load hitch can hand a job an unusually large elapsed time in one `Tick`. Clamp it to a few periods of your own cadence before using it.

## 5. Split a job the game reads every frame

If code outside the scheduler reads a deferrable job's result every frame, that result is stale on any skipped frame. Give the invariant its own cheap `mustRun` job, and keep the expensive work in a separate deferrable job.

## 6. Choose a cadence by the cost of staleness

Cadence and importance are unrelated. Ask what a reader sees if the value is one period old; that answer is the cadence.

## 7. Slew a control whose effect outlives the decision; never step it

If a job writes state that persists, emits or accumulates longer than its own cadence, slew it toward a target instead of stepping it from a live reading.

## 8. `DONE` cancels a sliced job; it does not pause it

Returning `Step.DONE` retires the job. To pause and resume instead, call `Handle.setPaused`: it keeps the job's cost average, deferral count, run count and peak cost, where cancelling and re-registering reset them.

## 9. Return `MORE` only with work in hand; return `YIELD` while waiting on anything external

`Step.MORE` is stepped again immediately, as long as the budget lasts. `Step.YIELD` waits for the next pump; use it whenever the next step depends on a load, an empty queue or another thread.

## 10. Do not `YIELD` before a step's cheap rejects

Return `Step.YIELD` only once a step is committed to real work, not on its first guard.

## 11. On teardown, cancel before you revert

Call `Scheduler.cancelAllFor(owner)` to stop every job under one owner on every lane; it is safe to call from inside your own job and returns the count stopped. Call `WorkPool.cancelAllFor(owner)` as well, to drop that owner's queued and finished pool work. Use one stable owner string per consumer, paired with a namespaced `JobSpec.withLabel`.

`WorkPool.cancelAll()` drops every owner's work; call it only if you own that `WorkPool`.

## 12. Labels are not unique

Two jobs may share a `JobSpec.withLabel` label. A tool that aggregates by label must sum matching entries, not overwrite one with another.

## 13. Read the counters together

- `Handle.runs()` proves a job ran, even one with no visible output.
- `peakCostNanos()` beside `averageCostNanos()` tells "cheap, sometimes expensive" from "always expensive".
- `overruns()` beside `deferrals()` separates a job that does not fit from one that keeps catching up. Both compare against the time a job was actually given: a sliced job's starvation chunk, and a `mustRun` job entered on an already-emptied lane, run outside the budget and are never counted as an overrun.
- `registrationIndex()` tells same-labelled jobs apart. Pass it to `Stagger.forNanos` to phase your own sub-work; never to `Stagger.forPumps`.

`CoreLog.setVerbose(true)` makes `Scheduler` print one row per job per lane, every five seconds.

## 14. `hasTimeLeft()` reads a snapshot; it is not a countdown

It answers the same thing on every pass of one entry; never loop on `while (tick.hasTimeLeft())`. `Tick.remainingNanos()` changes only between entries. A sliced job does one batch per step and returns. A periodic job that loops sizes its batch against the figure it was handed, or derives its own deadline as `System.nanoTime()` plus it, which only holds if your own scheduler runs on `System::nanoTime`.

`Tick.overBudget()` is true only in a `mustRun` job. Do the smallest useful unit and return.

## 15. `Lane.RENDER` pumps when the HUD is drawn, and on a menu screen with no world

In a world it pumps once a frame when the HUD is drawn, and takes zero pumps while the HUD is hidden; the pause, inventory and map screens still pump it. With no level loaded, from version 1.0.2, it is pumped once a frame by a screen hook (the title screen and every other menu), with a budget of the render share (one third by default) of a 60 frames a second frame, never above `renderCeilingMicros`, and the floor only once the menu already runs over 60 frames a second; `-Dsandpaper.menuPump=off` turns that hook off. It still takes zero pumps under the Mojang splash and the resource reload (an overlay, not a screen) and under a loading screen once a level is loaded without a HUD. Frames keep presenting throughout: a RENDER job does not slow down, it stops, and nothing counts or reports the gap.

- `mustRun` on `Lane.RENDER` means every frame a pump happens (the HUD is drawn in a world; a menu screen with no world, unless `-Dsandpaper.menuPump=off`), not every displayed frame; there is no pump under the splash, the resource reload, a hidden HUD or a loading screen with a level, so an invariant that must hold through those, or a null world check, belongs on `Lane.TICK` instead.
- Do not read a RENDER job's `runs()`, or a RENDER lane pump count, as liveness or a deadline.
- A `Cadenced` value is stale on the first frame after the HUD returns or a world loads; that is expected.
- Retime a RENDER job with `Handle.setPeriodPumps` or `setPeriodMillis`. A cancelled and re-registered job waits for the lane's next pump, and loses its counters.

## 16. `withStarvationMillis` is a floor on a deferrable job, exact on a sliced one

A deferrable job's declared value scales by 1.0 to 1.5 at registration; ask for the latest escalation you can tolerate divided by 1.5, not the number you want to see. Zero is unaffected. A sliced job's starvation chunk uses the declared value unscaled, and `JobSpec.DEFAULT_STARVATION_NANOS` scales the same way when a job never calls this method.

## 17. Contribute a settings page through `SettingsContributor`

The public surface is exactly `SettingsContributor`, `SettingsPage`, `SettingsGroup` and `OptionSpec`, in `dev.sandpaper.api.settings`. `dev.sandpaper.client.gui` and its `options` package (`AeroConfigScreen`, `AeroPainter`, `AeroTheme`, `AeroSurface`, `AeroGameSurface`, `SettingsContributions`, `Opt`, `OptPage`) are private and may change at any time; do not depend on any of it.

Implement `SettingsContributor` with a no-argument constructor and name the class under the `sandpaper-settings` entrypoint in your `fabric.mod.json`; Sandpaper asks the loader for it each time the screen opens.

- Sandpaper's own pages draw first, then contributors sorted by mod id; a page id used twice drops the later one and logs it.
- Constructing your class, calling `pages()` and reading the pages you return happen inside one guard: a throw there costs only your section. After the screen opens, each button, listener, availability check and sink instead runs inside its own guard.
- `pages()` and `save()` run on the client thread and must not block or open a screen. A `Button`'s `action` may do either: it runs later, on a click.
- One keybind, `key.sandpaper.open_settings` (category `sandpaper:main`), opens the screen for every contributor; add no keybind of your own for it.

## 18. `OptionSpec` describes a row; it does not draw it

`OptionSpec` is sealed to eight kinds: `Toggle`, `IntSlider`, `FloatSlider`, `IntField`, `Choice`, `Text`, `Colour` and `Button`. An exhaustive switch over it outside this package needs a new branch if a ninth kind ever ships.

- `available()` is on every kind and is asked continuously; return `true` for a row you never disable.
- `showing` is on every kind but `Button`, so one row can display a value another row is about to take.
- There is no text-only row; use a `SettingsGroup` for a heading.
- `IntSlider` and `FloatSlider` take a `label` function for their own readout; a null one draws the bare number.
- `IntSlider` clamps an out-of-range value; `IntField` refuses one instead, at commit.
- `Button`'s `action` runs unstaged, before any sink has run; opening a screen from it drops every pending edit on the current one.

## 19. Nothing is written until Apply

Sinks run, then `save()` runs once per contributor, when the reader presses Apply or Done. Escape commits nothing.

- An availability rule cannot read another row's staged value from your config; track it yourself, from that row's change listener, into a field of your own.
- Every staged value settles to a fixed point before any availability rule is asked.
- The staging sweep stops once nothing changes, and guards against re-entering itself. It gives up at a ceiling of at least 13 passes (higher on a page with many availability rules) if a supplier keeps disagreeing with what it was last told; a contributor that reaches it is logged and left half-staged, still usable.
- Return `false` from `save()` on a failed write; the screen stays open rather than closing over a lost setting.

## 20. `ValuesJob` is a periodic callback of five primitives

`ValuesJob` is a functional interface for periodic work. Its method receives five primitive components, not a `Tick` object. Pass it only to `Scheduler.registerValues`. Rule 1 applies: call it on the pumping thread only.

## 21. `ValuesStep` is a sliced callback of five primitives

`ValuesStep` is a functional interface for sliced work. Its method receives five primitive components and returns a `Step`. Rules 8, 9 and 10 apply to the return value. A `null` return is read as `Step.YIELD`.

## 22. `Scheduler.registerValues(JobSpec, ValuesJob)` registers periodic values work

It registers periodic values work on the pumping thread. With the startup switch `sandpaper.tickValues` off, the registration adapts once to the old record road, and each call still builds its `Tick` as before. With the switch on, the scheduler calls the callback directly and builds no `Tick` for the call. Rule 13 applies to the counters.

## 23. `Scheduler.registerSlicedValues(JobSpec, ValuesStep)` registers sliced values work

It registers sliced values work on the pumping thread. It follows the same switch and adapter rules as rule 22. Its step is handled as in rule 8 (`DONE`), rule 9 (`MORE` and `YIELD`) and rule 10.

## 24. `Scheduler.tickValuesEngaged()` counts direct values calls

It is a pumping-thread read of the number of direct values dispatches across all lanes of one scheduler. A direct call counts even when the callback throws. With the switch off the count is 0. A read from another thread is logged once, under the same rule as the other checked calls in rule 1.

## 25. `sandpaper.tickValues` is a startup switch, off by default

The system property `sandpaper.tickValues` is read once, when the scheduler class initializes. It is off by default. Set it to `true` on the JVM command line before Sandpaper starts. Changing the property later has no effect on a running scheduler. The switch changes only how values work is dispatched; it does not change what a job receives or returns.

## 26. `FramePart` runs one indexed part of a batch

`FramePart` is a functional interface with one method, `boolean tryRun(int partIndex)`. Pass it only to `Scheduler.registerFrameSpread`. The scheduler attempts parts in ascending index order on the owner thread, and each part commits only once. Return `true` to commit the part and advance the index. Return `false` to yield the same index to a later matching lane pump. A throw before commit leaves that index safe to retry as well. Stage results until your own commit point when partial visibility would be incorrect. Only bounded, deferrable work belongs in this API.

## 27. `FrameSpreadHandle` controls one reusable batch

`Scheduler.registerFrameSpread` returns a `FrameSpreadHandle`, which extends `Handle`. A final `true` makes the registration dormant; it does not cancel it. A dormant registration keeps its callback and its last late result. `rearm()` starts a new batch on the same registration: it returns `false` while a batch is active, while a callback is in progress, or once the handle is cancelled, and `true` only for a dormant handle. A successful rearm resets part progress, the late result, engagement and retry marks, and belongs to the next matching pump, even when called from inside another callback. `isLate()` reports whether the batch missed its limit of eligible lane pumps; the result stays set through completion until a successful rearm clears it. Both calls are owner-thread only; a foreign thread is refused with `IllegalStateException` (rule 33). The inherited controls keep sliced semantics (rule 32).

## 28. `Scheduler.registerFrameSpread(JobSpec, int, int, FramePart)` registers a batch

It registers `parts` indexed parts on the spec's lane, paced over at most `maxPumps` eligible lane pumps when the startup switch is on (rule 35). `parts` and `maxPumps` must be positive; a null spec or work, or a nonpositive count, throws before the call claims an owner, consumes a registration index or publishes work. The first matching pump may begin service. Register from the owner thread outside a pump or inside a pump callback, or from any thread while the scheduler has no live owner; the first registration enrolls its caller as the exclusive owner (rule 33). With the switch off, the same finite, ordered parts run as ordinary unpaced sliced work, the legacy sliced starvation service is the safeguard, and no completion deadline applies.

## 29. A spread deadline counts lane pumps, not displayed frames

Opportunities count only on the batch's own lane, starting at the first matching pump after registration or rearm. `Lane.TICK` counts ticks. `Lane.RENDER` counts HUD pumps in a world and menu pumps with no world, and takes zero pumps while the HUD is hidden, under an overlay, or under a loading screen with a level and no HUD (rule 15); a displayed-frame promise needs an integration that owns one pump per displayed frame. An eligible pump counts even at zero budget. A batch paused at pump admission does not age. A pause or cancel during a callback prevents further service while it holds. The scheduler cannot infer missing host pumps; a miss reports late and permits catch-up, it does not skip work or promise progress while a lane is stopped or an input never becomes ready.

## 30. Pacing budget and retry

With the switch on, each batch holds a stable phase `0 <= phase < maxPumps` from its registration order. After `p` eligible pumps, capped at `maxPumps`, the cumulative target is `((long) p * parts + phase) / maxPumps`; an attempt requires `partIndex < target`. The target starts at zero and reaches `parts` at `maxPumps`. Parts run in the existing round-robin sliced drain, at most one part per entry visit, with elapsed cost debited after each attempt and the existing per-pump step cap. A `false` return yields the index to a later pump and is not attempted again on the same pump. A throw uses the existing three-strike policy and `neverDrop`: the final failure cancels a normal job, and a kept job retries the same index on a later pump. A late, unfinished batch can also use later positive residual budget up to its target. With the switch off, no age or target is calculated.

## 31. A miss reports late and shares one free step

When the `maxPumps`-th eligible pump ends with parts outstanding, the batch is marked late once and joins an overdue queue. Completion within the limit is on time. Later qualifying pumps grant at most one outside-budget step per lane pump, shared with the legacy sliced starvation service; when both have a candidate, the grant alternates between them. The first eligible overdue batch is served and moves to the tail of the queue if unfinished; paused, cancelled, dormant, already-attempted, yielded and failed entries are skipped for that pump. Each grant counts in the lane's `freeChunks`. `isLate()` stays true through completion until rearm. With the switch off, no batch is marked late; the legacy sliced starvation service serves the work instead.

## 32. Inherited controls keep sliced semantics

A `FrameSpreadHandle` is a sliced registration. `cancel()` and `setPaused(boolean)` are safe from any thread, as on `Handle`; cancel reaches pending, active and dormant registrations, and so do `cancelAllFor` and `cancelAll`. The run counters read as on a sliced job. Periodic priority, `mustRun`, retiming with `setPeriodMillis` or `setPeriodPumps`, and millisecond deadline escalation do not schedule a spread batch. `expedite()` throws `IllegalStateException`, as on any sliced handle.

## 33. Strict owner enrollment and dead-owner handover

The first `registerFrameSpread` enrolls its caller as the exclusive owner. While a spread handle is enrolled, a foreign pump or registration through any entry point, and a reentrant or overlapping pump, throw `IllegalStateException` before lists, clocks or indices change; the same owner check guards the bulk cancellation paths. This is stricter than rule 1: the call is refused, not logged. Individual handle `cancel()` and `setPaused` remain available from another thread. A registration or rearm from the owner inside a callback keeps the same owner. When the owner thread has ended, a quiescent boundary lets a new thread claim ownership; a live owner is never displaced. When the last spread registration is cancelled and swept, the scheduler returns to the rule 1 behavior.

## 34. Lifetime counters

`frameSpreadEngaged()`, `frameSpreadLate()` and `frameSpreadCompletedParts()` are owner-thread lifetime reads across all lanes of one scheduler, with saturating counts. Engaged counts one per batch when its paced target is first evaluated, even if no part runs that pump. Late counts one per batch that misses its limit. Completed parts counts `true` commits. All three count only with the switch on; with the switch off they stay zero. A foreign read throws `IllegalStateException` (rule 33); before any owner exists the read returns zero. `LaneStats` keeps its public shape and `tickValuesEngaged` keeps its meaning.

## 35. `sandpaper.frameSpread` is a startup switch, off by default

The system property `sandpaper.frameSpread` is read once, when the scheduler class initializes. It is off by default. Set it to `true` on the JVM command line before Sandpaper starts. Changing the property later has no effect on a running scheduler. With the switch off, `registerFrameSpread` still runs the same finite, ordered parts as ordinary unpaced sliced work and builds no `Tick` for the call. With the switch on, only eligible-pump debt qualifies a batch for the shared outside-budget grant. Existing periodic priority, must-run precedence, retiming, starvation and escalation stay as they are.

## 36. The background worker pool takes every processor but one, at normal priority, with no upper cap

From version 1.0.2, `Background.threads()` and `WorkPool.defaultThreads()` return the processor count minus `Background.HEADROOM` (1, left for the render thread), never below one. `Background.PRIORITY` is `Thread.NORM_PRIORITY`: a worker below normal made offloaded work slower than the same work on one core. `Background.MAX_THREADS` and `WorkPool.MAX_THREADS` (6) are removed in 1.0.2: a source that names either does not compile against it, and a class built against 1.0.1 keeps the inlined 6. Size by `Background.threads()` and do not assume a ceiling; the settings slider keeps its own maximum of 32. On Windows a guard keeps workers off the render thread's core and follows it (see `RenderCoreGuard`); Windows treats CPU sets as advisory, so this reduces and does not forbid a worker on that core.
