# Sandpaper API index

Sandpaper's public types and their signatures.

## `WorkPool`

`dev.sandpaper.core.WorkPool`, a class. Source: `src/main/java/dev/sandpaper/core/WorkPool.java`.

_What it is for:_ The shared background thread pool: submit work, then drainCompleted applies results on whichever thread calls it, and that thread may change once the old one has ended.

| Signature | What it is for |
|---|---|
| `Stats stats()` | Snapshot of thread counts and timing totals. |
| `String threadsLine()` | Thread counts and timing totals, in microseconds, as one line. |
| `boolean closeWithin(long timeout, TimeUnit unit)` | Stops within timeout; unapplied finished work is discarded. |
| `boolean hasPending()` | True if work is queued, finished but unapplied, or a drop callback is waiting. |
| `boolean isCrashed()` | True if compute threw instead of returning a value. |
| `boolean isDroppedUnrun()` | True if dropped before it ran. |
| `boolean isEnded()` | True once this receipt's last submission is fully finished. |
| `boolean submit(String owner, Supplier<T> compute, Consumer<T> apply)` | Submits owned work; compute runs on a worker, apply runs at drain. |
| `boolean submit(String owner, Supplier<T> compute, Consumer<T> apply, Receipt receipt)` | Same, with a receipt marked if dropped unrun. |
| `boolean submit(String owner, Supplier<T> compute, Consumer<T> apply, Receipt receipt, Runnable onDrop, Consumer<T> onComputedDrop)` | Same, plus onDrop if never run and onComputedDrop if run but discarded. |
| `boolean submit(Supplier<T> compute, Consumer<T> apply)` | Submits unowned work; compute runs on a worker, apply runs at drain. |
| `boolean submit(Supplier<T> compute, Consumer<T> apply, Receipt receipt)` | Same, with a receipt marked if dropped unrun. |
| `boolean submit(Supplier<T> compute, Consumer<T> apply, Runnable onDrop)` | Same, plus onDrop run if the work is dropped before it runs. |
| `boolean submit(Supplier<T> compute, Consumer<T> apply, Runnable onDrop, Consumer<T> onComputedDrop)` | Same, plus onComputedDrop run with the value if it ran but was discarded. |
| `boolean test(Runnable queued)` | Internal: matches a queued task for cancellation. |
| `boolean test(Task<?> finished)` | Internal: matches a finished, unapplied task for cancellation. |
| `int cancelAll()` | Discards every queued and finished-but-unapplied task; running work keeps running. |
| `int cancelAllFor(String owner)` | Same, for one owner's work only. |
| `int drainCompleted(int maxItems)` | Applies up to maxItems finished results; returns the count applied. |
| `int queueCapacity()` | This pool's queue capacity, fixed at construction. |
| `nested: WorkPool.Receipt (class), WorkPool.Stats (record)` | Receipt: a per-submit token. Stats: the snapshot stats() returns. |
| `record WorkPool.Stats(int configuredThreads, int liveThreads, int peakThreads, int active, int queued, int awaitingApply, long submitted, long finished, long failed, long applied, long rejected, long discarded, long queueWaitMicros, long computeMicros, long applyWaitMicros, long applyAttempts)` | Thread counts, queue depth, and cumulative timing totals stats() returns. |
| `static int defaultThreads()` | The thread count a no-argument WorkPool uses. |
| `void close()` | AutoCloseable close: closeWithin with a fixed grace period. |
| `void rearm()` | Readies this receipt for one more submit. |
| `void rejectedExecution(Runnable task, ThreadPoolExecutor pool)` | Internal: marks a task refused instead of throwing. |
| `void run()` | Internal: a worker thread's task body; runs compute and queues its result. |
| `void wakeOnResult(Runnable hook)` | Runs hook once after each result or drop is queued. |

## `Sandpaper`

`dev.sandpaper.Sandpaper`, a class. Source: `src/main/java/dev/sandpaper/Sandpaper.java`.

_What it is for:_ Sandpaper's static entry point: the shared Scheduler and WorkPool, the live SandpaperConfig, and the settings save path.

| Signature | What it is for |
|---|---|
| `static Scheduler scheduler()` | Returns sandpaper's shared job scheduler; safe to call from mod init. |
| `static WorkPool workPool()` | Returns sandpaper's shared thread pool; throws if called before it is installed. |
| `static boolean hasPendingBackgroundSave()` | True while a background save is queued or being written. |
| `static boolean isReady()` | True once the work pool is installed. |
| `static boolean lastBackgroundSaveSucceeded()` | Result of the most recently finished background save. |
| `static boolean save()` | Writes settings from the client thread. |
| `static boolean saveInBackground(Runnable onFailure)` | Queues a settings write on a worker thread; onFailure may run on that thread. |
| `static dev.sandpaper.config.SandpaperConfig config()` | Returns the live, mutable SandpaperConfig instance. |
| `static void carryBackgroundSaveFailures(java.util.function.UnaryOperator<Runnable> backToTheSaveThatStartedThem)` | Wraps every saveInBackground onFailure so a coalesced save still reports to its own caller. |

## `Tick`

`dev.sandpaper.core.Tick`, a record. Source: `src/main/java/dev/sandpaper/core/Tick.java`.

_What it is for:_ The per-run snapshot sandpaper's scheduler hands a job on every entry.

| Signature | What it is for |
|---|---|
| `boolean first()` | True until this job completes a run. |
| `boolean hasTimeLeft()` | Snapshot of remaining budget at entry. |
| `boolean overBudget()` | Reachable only for mustRun jobs. |
| `double sinceLastRunSeconds()` | sinceLastRunNanos expressed in seconds. |
| `record Tick(long sinceLastRunNanos, long pumpsSinceLastRun, boolean catchUp, long remainingNanos, long runIndex)` | Time and pumps since the last run, catch-up state, remaining budget, completed-run count. |

## `Lane`

`dev.sandpaper.core.Lane`, a enum. Source: `src/main/java/dev/sandpaper/core/Lane.java`.

_What it is for:_ Which per-pump budget a job runs under; passed to JobSpec to choose a job's lane.

| Signature | What it is for |
|---|---|
| `RENDER` | Pumps once per HUD extraction, not per frame; some client states get no pumps. |
| `TICK` | Client tick, twenty times a second; world state safe to read here. |

## `JobSpec`

`dev.sandpaper.core.JobSpec`, a class. Source: `src/main/java/dev/sandpaper/core/JobSpec.java`.

_What it is for:_ Immutable, chainable description of how one job should be scheduled.

| Signature | What it is for |
|---|---|
| `JobSpec mustRun()` | Marks this job as running every time it is due, outside the budget. |
| `JobSpec neverDrop()` | Keeps this job registered no matter how often it throws. |
| `JobSpec withDeadlineMillis(long millis)` | Sets the max time since last clean run before it forces through. |
| `JobSpec withLabel(String l)` | Sets this job's display label; defaults to "job". |
| `JobSpec withOwner(String o)` | Sets the owner id used by cancelAllFor. |
| `JobSpec withPriority(Priority p)` | Sets this job's ordering weight against other deferrable jobs. |
| `JobSpec withStarvationMillis(long millis)` | Sets the overdue time after which this job jumps the queue. |
| `Lane lane()` | Which lane this job pumps on. |
| `Priority priority()` | This job's priority weight, set by withPriority or NORMAL by default. |
| `String label()` | This spec's label, or "job" if never set. |
| `String owner()` | This spec's owner id, or empty string if never set. |
| `boolean hasDeadline()` | Whether withDeadlineMillis has been called. |
| `boolean isMustRun()` | Whether mustRun has been called. |
| `boolean isNeverDropped()` | Whether neverDrop has been called. |
| `boolean timeBased()` | True if built with everyMillis rather than everyPumps or everyPump. |
| `long deadlineNanos()` | The escalation deadline in nanoseconds, or zero if never set. |
| `long periodNanos()` | This job's cadence in nanoseconds, if time-based; zero otherwise. |
| `long periodPumps()` | This job's cadence in pumps, if pump-counted; zero otherwise. |
| `long starvationNanos()` | This job's starvation deadline in nanoseconds. |
| `static JobSpec everyMillis(Lane lane, long millis)` | Starts a time-based spec, due every millis milliseconds. |
| `static JobSpec everyPump(Lane lane)` | Starts a spec due every pump. |
| `static JobSpec everyPumps(Lane lane, int pumps)` | Starts a pump-counted spec, due every pumps pumps. |

## `Background`

`dev.sandpaper.core.Background`, a class. Source: `src/main/java/dev/sandpaper/core/Background.java`.

_What it is for:_ How background work is given threads: sized down from available processors, lowered priority, daemon.

| Signature | What it is for |
|---|---|
| `static T call(Callable<T> work)` | Runs work on the calling thread at lowered priority; restores prior priority after. |
| `static Thread thread(Runnable work, String name)` | Builds a daemon thread with name at lowered priority; caller starts it. |
| `static ThreadFactory factory(String prefix)` | Factory building threads numbered from prefix, each at lowered priority. |
| `static int threads()` | How many threads background work may have. |
| `static void run(Runnable work)` | Runs work on the current thread at lowered priority; restores prior priority after. |

## `Step`

`dev.sandpaper.core.Step`, a enum. Source: `src/main/java/dev/sandpaper/core/Step.java`.

_What it is for:_ What a sliced job's step(Tick) return value tells the scheduler to do next.

| Signature | What it is for |
|---|---|
| `DONE` | No more work; job retired and will not step again. |
| `MORE` | More work and stepping now makes progress; may re-step this pump. |
| `YIELD` | More work but not this pump; eligible again next pump. |

## `SettingsPage`

`dev.sandpaper.api.settings.SettingsPage`, a record. Source: `src/main/java/dev/sandpaper/api/settings/SettingsPage.java`.

_What it is for:_ One entry in the settings screen's left column, and the groups listed under it.

| Signature | What it is for |
|---|---|
| `record SettingsPage(Identifier id, Component title, List<SettingsGroup> groups, Identifier icon)` | id and title required; groups non-null, non-empty; icon optional. |

## `SettingsGroup`

`dev.sandpaper.api.settings.SettingsGroup`, a record. Source: `src/main/java/dev/sandpaper/api/settings/SettingsGroup.java`.

_What it is for:_ A heading on a settings page and the rows grouped under it.

| Signature | What it is for |
|---|---|
| `record SettingsGroup(Component title, Component description, List<OptionSpec> options)` | title required; options non-null, non-empty; description optional. |

## `OptionSpec`

`dev.sandpaper.api.settings.OptionSpec`, a interface. Source: `src/main/java/dev/sandpaper/api/settings/OptionSpec.java`.

_What it is for:_ The settings API: a sealed set of row types a mod constructs, one instance per settings row.

| Signature | What it is for |
|---|---|
| ` checkedBounds("slider", "offers", "the slider cannot show", min, max, defaultValue)` | Internal: FloatSlider's bounds check. |
| ` checkedBounds(kind, rejects, cannot, min, max, defaultValue)` | Internal: shared bounds check for IntSlider and IntField. |
| ` checkedStep(step)` | Internal: rejects a step of zero or less. |
| ` this(name, description, defaultValue, current, sink, available, onChange, null)` | Toggle/Text constructor: no showing supplier. |
| ` this(name, description, defaultValue, current, sink, available, onChange, showing, null)` | Text constructor: showing supplied, no refusing. |
| ` this(name, description, defaultValue, current, sink, min, max, null, null, null)` | IntField constructor: always available, no listener, no showing. |
| ` this(name, description, defaultValue, current, sink, min, max, step, available, onChange, null, null)` | IntSlider/FloatSlider constructor: no label formatter, no showing. |
| ` this(name, description, defaultValue, current, sink, min, max, step, null, null, null, null)` | IntSlider/FloatSlider constructor: always available, no listener, no showing. |
| ` this(name, description, defaultValue, current, sink, null, null, null)` | Toggle/Text constructor: always available, no listener, no showing. |
| ` this(name, description, label, action, null)` | Button constructor: always clickable. |
| ` this(name, description, labels, defaultIndex, current, sink, available, onChange, null)` | Choice constructor: no showing supplier. |
| ` this(name, description, labels, defaultIndex, current, sink, null, null, null)` | Choice constructor: always available, no listener, no showing. |
| ` this(name, description, shippedArgb, current, sink, available, onChange, null)` | Colour constructor: no showing supplier. |
| ` this(name, description, shippedArgb, current, sink, null, null, null)` | Colour constructor: always available, no listener, no showing. |
| `BooleanSupplier available()` | Whether the reader may touch this row right now. |
| `Component description()` | Explanatory text under the row's name, or null for none. |
| `Component name()` | The row's own label, always shown. |
| `record OptionSpec.Button(Component name, Component description, Component label, Runnable action, BooleanSupplier available)` | A row that runs an action on click; name, label and action required. |
| `record OptionSpec.Choice(Component name, Component description, List<Component> labels, int defaultIndex, int current, Consumer<Integer> sink, BooleanSupplier available, Consumer<Integer> onChange, Supplier<Integer> showing)` | A fixed list of named choices, bound by position. |
| `record OptionSpec.Colour(Component name, Component description, int shippedArgb, String current, Consumer<String> sink, BooleanSupplier available, Consumer<String> onChange, Supplier<String> showing)` | A colour swatch stored as text: a preset name or hex digits. |
| `record OptionSpec.FloatSlider(Component name, Component description, float defaultValue, float current, Consumer<Float> sink, float min, float max, float step, BooleanSupplier available, Consumer<Float> onChange, Function<Float, Component> label, Supplier<Float> showing)` | A floating-point slider between min and max in steps of step. |
| `record OptionSpec.IntField(Component name, Component description, int defaultValue, int current, Consumer<Integer> sink, int min, int max, BooleanSupplier available, Consumer<Integer> onChange, Supplier<Integer> showing)` | A whole number the reader types, clamped to [min, max]. |
| `record OptionSpec.IntSlider(Component name, Component description, int defaultValue, int current, Consumer<Integer> sink, int min, int max, int step, BooleanSupplier available, Consumer<Integer> onChange, Function<Integer, Component> label, Supplier<Integer> showing)` | An integer slider between min and max in steps of step. |
| `record OptionSpec.Text(Component name, Component description, String defaultValue, String current, Consumer<String> sink, BooleanSupplier available, Consumer<String> onChange, Supplier<String> showing, Function<String, Component> refusing)` | A free-text field; sink is the final check on any value. |
| `record OptionSpec.Toggle(Component name, Component description, boolean defaultValue, boolean current, Consumer<Boolean> sink, BooleanSupplier available, Consumer<Boolean> onChange, Supplier<Boolean> showing)` | A plain on or off switch. |
| `throw new IllegalArgumentException("a " + kind + " from " + min + " to " + max + " " + rejects + " nothing at all.")` | Thrown when min is above max, by IntSlider, IntField or FloatSlider. |
| `throw new IllegalArgumentException("a choice with no choices in it.")` | Thrown by Choice's constructor when labels is empty. |
| `throw new IllegalArgumentException("a slider from " + min + " to " + max + " by " + step + ", shipping " + defaultValue + ", is not a " + "slider: one of those is NaN or infinite, and every bounds " + "check here is a comparison that NaN passes.")` | Thrown by FloatSlider's constructor on a NaN or infinite bound. |
| `throw new IllegalArgumentException("a step of " + step + " leaves the " + "slider with no position to snap to.")` | Thrown by checkedStep when step is zero or negative. |

## `Priority`

`dev.sandpaper.core.Priority`, a enum. Source: `src/main/java/dev/sandpaper/core/Priority.java`.

_What it is for:_ The tiebreaker among a lane's deferrable jobs when a pump's budget runs out.

| Signature | What it is for |
|---|---|
| `HIGH` | Drains before NORMAL and LOW when budget will not stretch to every due job; weight() is 2. |
| `LOW` | Drains after NORMAL and HIGH while none is starved; weight() is 0. |
| `NORMAL` | The default priority every JobSpec factory assigns; weight() is 1. |
| `int weight()` | The raw integer the scheduler compares to order deferrable jobs. |

## `Handle`

`dev.sandpaper.core.Handle`, a interface. Source: `src/main/java/dev/sandpaper/core/Handle.java`.

_What it is for:_ The control returned by Scheduler.register or registerSliced: pause, retime, cancel or inspect a job.

| Signature | What it is for |
|---|---|
| `String label()` | This job's label at registration. |
| `boolean isCancelled()` | Whether this job has been cancelled. |
| `boolean isPaused()` | Whether setPaused(true) is currently in effect. |
| `int registrationIndex()` | This job's position in registration order. |
| `long averageCostNanos()` | A damped average of how long one run takes, in nanoseconds. |
| `long deferrals()` | How many times this job was due but budget ran out first. |
| `long overruns()` | How many completed runs took longer than their given budget. |
| `long peakCostNanos()` | The single most expensive run this job has ever had. |
| `long periodMillis()` | This job's current period in milliseconds, if time-based. |
| `long periodPumps()` | This job's current period in pumps, if pump-counted. |
| `long runs()` | How many times this job has been entered, including throws. |
| `void cancel()` | Cancels this job so it will not run again. |
| `void expedite()` | Makes this job due on or before its lane's next pump. |
| `void setPaused(boolean paused)` | Pauses or unpauses this job. |
| `void setPeriodMillis(long millis)` | Retimes a time-based job. |
| `void setPeriodPumps(long pumps)` | Retimes a pump-counted job. |

## `SettingsContributor`

`dev.sandpaper.api.settings.SettingsContributor`, a interface. Source: `src/main/java/dev/sandpaper/api/settings/SettingsContributor.java`.

_What it is for:_ The interface a mod implements to add its own pages to sandpaper's settings screen.

| Signature | What it is for |
|---|---|
| `List<SettingsPage> pages()` | This mod's pages, read fresh every time the settings screen opens. |
| `boolean save()` | Writes this mod's settings; return false or throw to report failure. |
| `void askAgain()` | Re-polls this contributor's own rows right away. |

## `Scheduler`

`dev.sandpaper.core.Scheduler`, a class. Source: `src/main/java/dev/sandpaper/core/Scheduler.java`.

_What it is for:_ Runs registered jobs against one lane's per-pump time budget.

| Signature | What it is for |
|---|---|
| `Handle register(JobSpec spec, Job job)` | Registers periodic work and returns a Handle that controls it. |
| `Handle registerSliced(JobSpec spec, SlicedJob job)` | Registers work drained from budget periodic jobs leave over. |
| `LaneStats stats(Lane lane)` | Snapshot of one lane's lifetime totals. |
| `String label()` | This job's label, from JobSpec.withLabel or the default. |
| `boolean isCancelled()` | Whether this job has been cancelled. |
| `boolean isPaused()` | Whether this job is currently paused. |
| `int cancelAll()` | Cancels every job. |
| `int cancelAllFor(String owner)` | Cancels every job under one owner, on every lane. |
| `int compare(Entry a, Entry b)` | Internal: orders one lane's due deferrable jobs for a pump. |
| `int registrationIndex()` | This job's index in registration order, fixed for its life. |
| `int registrations()` | Registrations so far. |
| `long averageCostNanos()` | Damped moving average of this job's run cost, in nanoseconds. |
| `long deferrals()` | Count of times this job was due while its lane's budget was spent. |
| `long overruns()` | Count of runs costing more than this job's given budget. |
| `long peakCostNanos()` | The single most expensive run this job has ever had. |
| `long periodMillis()` | This job's current period in milliseconds. |
| `long periodPumps()` | This job's current period in pumps. |
| `long runs()` | Count of runs entered on this job, including throws. |
| `nested: Scheduler.LaneStats (record)` | See the record row below for its fields. |
| `record Scheduler.LaneStats(long pumps, long ran, long deferred, int periodic, int sliced, long freeChunks, long callbackNanos)` | One lane's running totals: pumps, runs, deferrals, live entries, callback time. |
| `void cancel()` | Cancels this one job. |
| `void expedite()` | Marks this job due immediately, without changing its period. |
| `void pump(Lane lane, long budgetNanos)` | Runs one pump of one lane within budgetNanos. |
| `void setPaused(boolean p)` | Pauses or resumes this job. |
| `void setPeriodMillis(long millis)` | Retimes an everyMillis job in place. |
| `void setPeriodPumps(long pumps)` | Retimes an everyPumps job in place. |

## `Cadenced`

`dev.sandpaper.core.Cadenced`, a class. Source: `src/main/java/dev/sandpaper/core/Cadenced.java`.

_What it is for:_ A value recomputed on a cadence and cached for cheap reads elsewhere.

| Signature | What it is for |
|---|---|
| `Handle handle()` | Registration for pausing, retiming or cancelling. |
| `T get()` | Last computed value, or the initial one; a plain volatile read. |
| `boolean isStopped()` | Whether recompute has been cancelled; a pause does not count. |
| `static Cadenced<T> everyOtherFrame(Scheduler scheduler, T initial, Supplier<T> compute)` | Recomputes on every second pump of RENDER. |
| `static Cadenced<T> everyOtherFrame(Scheduler scheduler, T initial, Supplier<T> compute, String label)` | Same, named for clearer log lines. |
| `static Cadenced<T> register(Scheduler scheduler, JobSpec spec, T initial, Supplier<T> compute)` | The general factory: pass any JobSpec to pick lane, cadence, priority or owner. |

## `Errand`

`dev.sandpaper.core.Errand`, a class. Source: `src/main/java/dev/sandpaper/core/Errand.java`.

_What it is for:_ Recurring work computed off the game thread and applied back on it, one outstanding send at a time.

| Signature | What it is for |
|---|---|
| `Handle handle()` | Cadence handle; null for an Errand built with on(WorkPool). |
| `boolean isOutstanding()` | Whether this Errand's last send has not yet seen an apply or a drop. |
| `boolean send(Supplier<T> compute, Consumer<T> apply)` | Sends one errand if none is out; false if one is already out or is refused. |
| `static Errand on(WorkPool pool)` | An Errand with no cadence; the caller drives every send() call. |
| `static Errand register(Scheduler scheduler, WorkPool pool, JobSpec spec, Supplier<T> compute, Consumer<T> apply)` | Registers recurring off-thread compute with on-game apply, on a cadence. |

## `SandpaperFonts`

`dev.sandpaper.client.font.SandpaperFonts`, a class. Source: `src/main/java/dev/sandpaper/client/font/SandpaperFonts.java`.

_What it is for:_ Owns sandpaper's replacement font: the on-disk FontLibrary, the selected face, and wrapped vanilla Font instances.

| Signature | What it is for |
|---|---|
| `static Font lettering(Font vanilla)` | Returns vanilla wrapped so its glyph lookups use the selected face, or unchanged if none applies. |
| `static FontLibrary fonts()` | Returns the shared FontLibrary, scanning fontDirectory() on first call. |
| `static FontResourcePack.Selection publishSelection()` | Recomputes and publishes the served font selection from the current config. |
| `static FontResourcePack.Selection selection()` | Returns the font selection sandpaper's resource pack currently serves. |
| `static Path fontDirectory()` | The folder sandpaper scans for font files. |
| `static RepositorySource packSource()` | Builds the RepositorySource that serves sandpaper's font resource pack. |
| `static boolean usableFace(String id)` | True if id names a loadable TrueType face. |
| `static void invalidateLettering()` | Drops the cached wrapped Font that lettering() builds. |
| `static void packSourceRegistered()` | Marks that sandpaper's font resource pack has been added to the pack repository. |
| `static void settingsSaved()` | Republishes the font selection and drops the cached lettering() Font. |

## `CoreLog`

`dev.sandpaper.core.CoreLog`, a class. Source: `src/main/java/dev/sandpaper/core/CoreLog.java`.

_What it is for:_ Where sandpaper's core package sends job failures and verbose detail.

| Signature | What it is for |
|---|---|
| `static boolean isVerbose()` | Returns whether extra detail is wanted now. |
| `static void install(BiConsumer<String, Throwable> where)` | Send all reports here instead of stderr. |
| `static void installVerbose(Consumer<String> where)` | Send verbose detail here instead of stderr. |
| `static void setVerbose(boolean on)` | Turns detail logging on or off. |

## `SandpaperConfig`

`dev.sandpaper.config.SandpaperConfig`, a class. Source: `src/main/java/dev/sandpaper/config/SandpaperConfig.java`.

_What it is for:_ The plain-data settings object sandpaper loads from and saves to JSON.

| Signature | What it is for |
|---|---|
| `List<String> normaliseAndListCorrections()` | Runs normalise() and returns one description per field it changed. |
| `SandpaperConfig normalise()` | Clamps and corrects every field to its valid range in place. |
| `boolean liquidTreatment()` | True when treatment is the liquid render treatment. |
| `int normalisedFloorMicros()` | Floor clamped so it never exceeds either ceiling. |
| `long floorNanos()` | The stored floorMicros field converted to nanoseconds. |
| `long renderCeilingNanos()` | renderCeilingMicros converted to nanoseconds. |
| `long tickCeilingNanos()` | tickCeilingMicros converted to nanoseconds. |
| `static FontScope normaliseFontScope(FontScope value)` | Returns value unchanged, or LETTERING if null. |
| `static SandpaperConfig load(Path path)` | Reads and parses path as JSON into a config, normalising it. |
| `static String normaliseColour(String value)` | Returns value unchanged if it is a known colour, else the default colour. |
| `static String normaliseFontId(String value)` | Returns value unchanged, or the empty default if null. |
| `static String normaliseTreatment(String value)` | Returns value unchanged if "aero" or "liquid", else "aero". |
| `static boolean overrideCeilingReachesAutoPick(int maxWorkerThreads, int autoPickCap)` | True when the manual ceiling reaches the auto-pick cap. |
| `static float normaliseFontOversample(float value)` | Clamps value to 1.0 through 4.0; NaN returns the default 2.0. |
| `static int normaliseFontSizePercent(int value)` | Clamps value to 25 through 300. |
| `static void warmSerializer()` | Warms the Gson write path at boot on the client thread. |
| `void copyFrom(SandpaperConfig other)` | Copies every settings field plus the unreadable flag into this instance. |
| `void save(Path path)` | Writes this config as JSON to path through a temp file and atomic move. |
