# The Sandpaper contract

Rules a caller of Sandpaper must follow: 1 to 16 for the scheduler and the work pool, 17 to 19 for the settings screen. Stop at 16 if you only schedule work.

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

## 15. `Lane.RENDER` pumps only when the HUD is drawn

It takes zero pumps with no level loaded, under a loading screen, and while the HUD is hidden; the pause, inventory and map screens still pump it. Frames keep presenting throughout: a RENDER job does not slow down, it stops, and nothing counts or reports the gap.

- `mustRun` on `Lane.RENDER` means every frame the HUD is drawn, not every frame; an invariant that must hold through a hidden HUD or a world load belongs on `Lane.TICK` instead.
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
