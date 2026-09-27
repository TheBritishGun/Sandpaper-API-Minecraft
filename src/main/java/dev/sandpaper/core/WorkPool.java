package dev.sandpaper.core;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
public final class WorkPool implements AutoCloseable {
    public static final int MAX_THREADS = Background.MAX_THREADS;
    public static final long SHUTDOWN_GRACE_MILLIS = 1_000L;
    private static final int MAX_SKIPPED_POLLS_PER_DRAIN = 64;
    // A task is the queue entry, the result and the drop envelope; a receipt may reuse it.
    private static final class Task<T> implements Runnable {
        // Only state cancellation can claim from.
        private static final int QUEUED = 0;
        // Compute in progress; teardown cannot claim it; still counts itself if the generation changes.
        private static final int RUNNING = 1;
        private static final int FINISHED = 2;
        // Terminal; the outcome is decided by which transition won, not by this value alone.
        private static final int DONE = 3;
        @SuppressWarnings("rawtypes")
        private static final AtomicIntegerFieldUpdater<Task> STATE =
                AtomicIntegerFieldUpdater.newUpdater(Task.class, "state");
        private final WorkPool pool;
        private final Owner owner;
        private final Receipt receipt;
        private long generation;
        private long ownerGeneration;
        // Nulled by release(), on every road: applied, dropped, or claimed by teardown.
        private Consumer<T> apply;
        // Nulled here right after use, ahead of release().
        private Supplier<T> compute;
        private Runnable onDrop;
        private Consumer<T> onComputedDrop;
        // Written by a worker; read only after the queue hands this task to the drainer.
        private T value;
        // Read and written only by the submitting thread.
        private boolean refused;
        // Only the CAS winner counts as having claimed it.
        private volatile int state;
        // Microsecond timestamp for queue and apply wait; wraps at 32 bits, about 71.6 minutes.
        private int stampMicros;
        // True while the executor's queue or a worker can still reach this task
        private volatile boolean inExecutor;
        Task(WorkPool pool, Owner owner, Receipt receipt, long generation,
             long ownerGeneration, Supplier<T> compute, Consumer<T> apply,
             Runnable onDrop, Consumer<T> onComputedDrop, int stampMicros) {
            this.pool = pool;
            this.owner = owner;
            this.receipt = receipt;
            load(generation, ownerGeneration, compute, apply, onDrop, onComputedDrop, stampMicros);
        }
        // Readies this task for one submission; nothing of its last one may still reach it
        void load(long generation, long ownerGeneration, Supplier<T> compute, Consumer<T> apply,
                  Runnable onDrop, Consumer<T> onComputedDrop, int stampMicros) {
            this.generation = generation;
            this.ownerGeneration = ownerGeneration;
            this.compute = compute;
            this.apply = apply;
            this.onDrop = onDrop;
            this.onComputedDrop = onComputedDrop;
            this.stampMicros = stampMicros;
            value = null;
            refused = false;
            inExecutor = true;
            state = QUEUED;
        }
        Owner owner() {
            return owner;
        }
        Receipt receipt() {
            return receipt;
        }
        // True if this caller won the claim; only the winner may act on it.
        boolean claim(int from) {
            return STATE.compareAndSet(this, from, DONE);
        }
        // Nulls the value, compute, apply and both drop callbacks; call only after winning the claim.
        void release() {
            value = null;
            apply = null;
            compute = null;
            onDrop = null;
            onComputedDrop = null;
        }
        void dropped() {
            if (queueDropped()) {
                pool.wake();
            }
        }
        // Queues onDrop; does not wake. Returns whether it queued a callback.
        boolean queueDropped() {
            Runnable callback = onDrop;
            release();
            if (callback == null) {
                endReceipt();
                return false;
            }
            onDrop = callback;
            pool.droppedCallbacks.add(this);
            return true;
        }
        // Keeps the value and callback for onComputedDrop until the drop runs.
        void droppedWithValue() {
            Consumer<T> callback = onComputedDrop;
            if (callback == null) {
                dropped();
                return;
            }
            T result = value;
            release();
            value = result;
            onComputedDrop = callback;
            pool.droppedCallbacks.add(this);
            pool.wake();
        }
        // Runs the drop callback this task was queued with, then ends its receipt
        void runDrop() {
            try {
                if (onComputedDrop == null) {
                    onDrop.run();
                } else {
                    onComputedDrop.accept(value);
                }
            } finally {
                release();
                endReceipt();
            }
        }
        // Ends a submission that never ran; the executor lets go only when no worker can reach it
        void endUnrun(boolean executorLetGo) {
            release();
            if (executorLetGo) {
                inExecutor = false;
            }
            endReceipt();
        }
        // Marks the receipt ended; call only once nothing of this submission can run again.
        void endReceipt() {
            if (receipt != null) {
                receipt.ended = true;
            }
        }
        @Override
        public void run() {
            if (!STATE.compareAndSet(this, QUEUED, RUNNING)) {
                // Reached only when a failed execute() has already claimed this task back.
                inExecutor = false;
                return;
            }
            // Queue wait timing starts here, now that nothing has claimed this task first.
            int enteredMicros = micros(pool.clock.getAsLong());
            pool.queueWaitMicros.addAndGet(span(enteredMicros - stampMicros));
            Throwable crash = null;
            try {
                value = compute.get();
            } catch (Throwable crashed) {
                crash = crashed;
            } finally {
                compute = null;
                // stampMicros now marks the start of the apply wait instead of the queue wait.
                int doneMicros = micros(pool.clock.getAsLong());
                pool.computeMicros.addAndGet(span(doneMicros - enteredMicros));
                stampMicros = doneMicros;
            }
            pool.finished.incrementAndGet();
            if (crash != null) {
                pool.failed.incrementAndGet();
                STATE.set(this, DONE);
                if (receipt != null) {
                    receipt.crashed = true;
                }
                dropped();
                CoreLog.error("Work pool task failed; its result is dropped", crash);
                inExecutor = false;
                return;
            }
            if (pool.epoch.get() != generation
                    || owner.generation.get() != ownerGeneration) {
                STATE.set(this, DONE);
                pool.discarded.incrementAndGet();
                droppedWithValue();
                inExecutor = false;
                return;
            }
            if (!pool.reserveApplySlot()) {
                STATE.set(this, DONE);
                pool.discarded.incrementAndGet();
                pool.reportOverflowOnce();
                droppedWithValue();
                inExecutor = false;
                return;
            }
            if (pool.epoch.get() != generation
                    || owner.generation.get() != ownerGeneration) {
                STATE.set(this, DONE);
                pool.awaiting.decrementAndGet();
                pool.discarded.incrementAndGet();
                droppedWithValue();
                inExecutor = false;
                return;
            }
            // No longer in the executor's queue by this point.
            STATE.set(this, FINISHED);
            pool.completed.add(this);
            if (pool.epoch.get() != generation) {
                if (claim(FINISHED)) {
                    droppedWithValue();
                    pool.awaiting.decrementAndGet();
                    pool.discarded.incrementAndGet();
                }
                inExecutor = false;
                return;
            }
            inExecutor = false;
            pool.wake();
        }
        void apply() {
            apply.accept(value);
        }
    }
    // Marks refusal instead of throwing.
    private static final class MarkRefused implements RejectedExecutionHandler {
        private static final ThreadPoolExecutor.AbortPolicy ABORT =
                new ThreadPoolExecutor.AbortPolicy();
        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor pool) {
            if (task instanceof Task<?> refused) {
                refused.refused = true;
                return;
            }
            ABORT.rejectedExecution(task, pool);
        }
    }
    // Tracks accepted work dropped unrun; no callback runs on the cancelling thread.
    public static final class Receipt {
        private final AtomicBoolean spent = new AtomicBoolean();
        // The task this receipt's submits reuse; written by the submitting thread
        private volatile Task<?> task;
        // Set on the discard path; one writer per receipt; safe to set again.
        private volatile boolean droppedUnrun;
        // True if dropped before running; false does not mean it will run.
        public boolean isDroppedUnrun() {
            return droppedUnrun;
        }
        private volatile boolean crashed;
        // True if compute threw instead of returning a value.
        public boolean isCrashed() {
            return crashed;
        }
        private volatile boolean ended;
        // True once nothing of the last submission can run again: applied, dropped with its
        // callback run if it had one, compute threw, refused by a full or closed queue, or a
        // failed execute() whose claim-back won. False before the first submit and after rearm().
        public boolean isEnded() {
            return ended;
        }
        // Readies this receipt for one more submit; clears isDroppedUnrun and isCrashed.
        // Call it on the submitting thread.
        // Throws IllegalStateException if this receipt was submitted and has not ended.
        public void rearm() {
            if (spent.get() && !ended) {
                throw new IllegalStateException("this WorkPool.Receipt's last submission has"
                        + " not ended."
                        + " Rearm once isEnded() is true, or make a new Receipt.");
            }
            droppedUnrun = false;
            crashed = false;
            ended = false;
            spent.set(false);
        }
    }
    private final ThreadPoolExecutor executor;
    // Public constructors default this to System::nanoTime.
    private final LongSupplier clock;
    private final Queue<Task<?>> completed = new ConcurrentLinkedQueue<>();
    private final Queue<Task<?>> droppedCallbacks = new ConcurrentLinkedQueue<>();
    private final int completedCapacity;
    private final int queueCapacity;
    // Never falls below the queue size, except during the close handshake.
    private final AtomicInteger awaiting = new AtomicInteger();
    // One report per overflow episode; clears when the queue empties.
    private final AtomicBoolean overflowReported = new AtomicBoolean();
    // Throttles repeated apply-failure reports; each failure still counts.
    private final AtomicBoolean applyFailureReported = new AtomicBoolean();
    // One report per streak of refusals; clears on success; latches after close.
    private final AtomicBoolean submitRefusalReported = new AtomicBoolean();
    // One drain thread at a time; a new one may take over once the old one has ended.
    private final AtomicReference<Thread> drainer = new AtomicReference<>();
    // Reported once per pool; never cleared.
    private final AtomicBoolean offThreadDrainReported = new AtomicBoolean();
    // Only one hook at a time; wakeOnResult() rejects a second, different one.
    private volatile Runnable onResult;
    private final AtomicBoolean secondWakeHookReported = new AtomicBoolean();
    // Reported once per pool; never reset.
    private final AtomicBoolean wakeHookFailureReported = new AtomicBoolean();
    // Counts queued tasks cancelled before running; excludes finished-queue removals.
    private final AtomicLong cancelledBeforeRunning = new AtomicLong();
    private final AtomicLong epoch = new AtomicLong();
    // Interned per name forever; the same name always returns the same instance.
    private static final class Owner {
        private final String name;
        private final AtomicLong generation = new AtomicLong();
        Owner(String name) {
            this.name = name;
        }
        String name() {
            return name;
        }
    }
    // Counts queued tasks matching owner; null owner means match all.
    private static final class ClaimQueued implements Predicate<Runnable> {
        private final Owner only;
        // Only touched during removeIf's single-threaded walk.
        private int claimed;
        // Set once a claimed task queued a drop callback; the caller wakes after removeIf.
        private boolean queuedCallback;
        ClaimQueued(Owner only) {
            this.only = only;
        }
        // Must return true when only is null, so cancelAll removes every entry.
        @Override
        public boolean test(Runnable queued) {
            if (queued instanceof Task<?> owned && (only == null || owned.owner() == only)
                    && owned.claim(Task.QUEUED)) {
                markDroppedUnrun(queued);
                owned.inExecutor = false;
                if (owned.queueDropped()) {
                    queuedCallback = true;
                }
                claimed++;
                return true;
            }
            return only == null;
        }
    }
    // Claims finished tasks for release; always one owner, never all.
    private static final class ClaimFinished implements Predicate<Task<?>> {
        private final Owner only;
        private int claimed;
        ClaimFinished(Owner only) {
            this.only = only;
        }
        @Override
        public boolean test(Task<?> finished) {
            if (finished.owner() == only && finished.claim(Task.FINISHED)) {
                finished.droppedWithValue();
                claimed++;
                return true;
            }
            return false;
        }
    }
    // Holds every owner name ever seen; never shrinks.
    private final java.util.concurrent.ConcurrentHashMap<String, Owner> owners =
            new java.util.concurrent.ConcurrentHashMap<>();
    // The sentinel for unowned work; not stored in the owners table.
    private final Owner unowned = new Owner("");
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong finished = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong discarded = new AtomicLong();
    // Totals, not averages; divide applyWaitMicros by applyAttempts for the mean.
    private final AtomicLong queueWaitMicros = new AtomicLong();
    private final AtomicLong computeMicros = new AtomicLong();
    private final AtomicLong applyWaitMicros = new AtomicLong();
    private final AtomicLong applyAttempts = new AtomicLong();
    public static int defaultThreads() {
        return Background.threads();
    }
    // Must be kept in sync by hand with SandpaperConfig.queueCapacity.
    static final int DEFAULT_QUEUE_CAPACITY = 256;
    public WorkPool() {
        this(defaultThreads(), DEFAULT_QUEUE_CAPACITY);
    }
    public WorkPool(int threads, int queueSize) {
        this(threads, queueSize, System::nanoTime);
    }
    WorkPool(int threads, int queueSize, LongSupplier clock) {
        this(threads, queueSize, clock, 30L, TimeUnit.SECONDS);
    }
    WorkPool(int threads, int queueSize, LongSupplier clock, long keepAlive, TimeUnit keepAliveUnit) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1, was " + threads);
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        ThreadFactory factory = Background.factory("sandpaper-worker-");
        this.executor = new ThreadPoolExecutor(
                threads, threads, keepAlive, keepAliveUnit,
                new ArrayBlockingQueue<>(queueSize), factory,
                new MarkRefused());
        this.completedCapacity = queueSize + threads;
        this.queueCapacity = queueSize;
    }
    // This pool's own queue capacity, fixed at construction; may now differ from
    // SandpaperConfig.queueCapacity if the setting changed since.
    // A bound, not a promise: a closed pool refuses everything, and another thread
    // may take the last free place first.
    public int queueCapacity() {
        return queueCapacity;
    }
    public <T> boolean submit(Supplier<T> compute, Consumer<T> apply) {
        return submitAs("", compute, apply, null, null, null);
    }
    public <T> boolean submit(Supplier<T> compute, Consumer<T> apply, Runnable onDrop) {
        return submitAs("", compute, apply, null, Objects.requireNonNull(onDrop, "onDrop"),
                null);
    }
    public <T> boolean submit(Supplier<T> compute, Consumer<T> apply, Runnable onDrop,
                              Consumer<T> onComputedDrop) {
        return submitAs("", compute, apply, null, Objects.requireNonNull(onDrop, "onDrop"),
                Objects.requireNonNull(onComputedDrop, "onComputedDrop"));
    }
    // Submits work with a receipt marked if dropped unrun; needs a fresh or rearmed receipt.
    public <T> boolean submit(Supplier<T> compute, Consumer<T> apply, Receipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        return submitAs("", compute, apply, receipt, null, null);
    }
    // Submits owned work; empty owner means unowned, null or blank owner throws.
    public <T> boolean submit(String owner, Supplier<T> compute, Consumer<T> apply) {
        checkOwner(owner);
        return submitAs(owner, compute, apply, null, null, null);
    }
    // Submits owned work with a receipt marked if dropped unrun.
    public <T> boolean submit(String owner, Supplier<T> compute, Consumer<T> apply,
                              Receipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        checkOwner(owner);
        return submitAs(owner, compute, apply, receipt, null, null);
    }
    // Submits owned work that is told when it is dropped; the receipt may be null.
    // onDrop runs if the work was dropped before it ran; onComputedDrop, with the value,
    // if it ran and the result was thrown away by cancelAllFor, a full apply queue, a
    // stale result at the drain, or a close. Both run where drainCompleted runs, never
    // on the cancelling thread.
    public <T> boolean submit(String owner, Supplier<T> compute, Consumer<T> apply,
                              Receipt receipt, Runnable onDrop, Consumer<T> onComputedDrop) {
        checkOwner(owner);
        return submitAs(owner, compute, apply, receipt,
                Objects.requireNonNull(onDrop, "onDrop"),
                Objects.requireNonNull(onComputedDrop, "onComputedDrop"));
    }
    private static void checkOwner(String owner) {
        // Empty string is the unowned sentinel; blank-but-not-empty is refused.
        boolean blankButNotTheUnownedSentinel = owner != null
                && owner.isBlank() && !owner.isEmpty();
        if (owner == null || blankButNotTheUnownedSentinel) {
            throw new IllegalArgumentException("owner must not be "
                    + (owner == null ? "null" : "blank")
                    + "."
                    + " Omit the owner - submit(compute, apply) - for"
                    + " unowned work.");
        }
    }
    private Owner ownerFor(String who) {
        if (who.isEmpty()) {
            return unowned;
        }
        Owner known = owners.get(who);
        return known != null ? known : owners.computeIfAbsent(who, Owner::new);
    }
    // The receipt's task, reusable only when this pool and owner match and nothing can still reach it.
    @SuppressWarnings("unchecked")
    private <T> Task<T> keptTask(Receipt receipt, Owner owner) {
        if (receipt == null) {
            return null;
        }
        Task<?> kept = receipt.task;
        if (kept == null || kept.pool != this || kept.owner != owner || kept.inExecutor) {
            return null;
        }
        return (Task<T>) kept;
    }
    // Callers with no owner must pass an empty string, not null.
    private <T> boolean submitAs(String who, Supplier<T> compute, Consumer<T> apply,
                                 Receipt receipt, Runnable onDrop, Consumer<T> onComputedDrop) {
        if (receipt != null && !receipt.spent.compareAndSet(false, true)) {
            throw new IllegalArgumentException("this WorkPool.Receipt has already been"
                    + " submitted."
                    + " Make a new Receipt per submit, or call rearm() once"
                    + " isEnded() is true.");
        }
        long generation = epoch.get();
        Owner owner = ownerFor(who);
        long ownerGeneration = owner.generation.get();
        // Clock read here marks the end of overhead and start of queue wait.
        int stampMicros = micros(clock.getAsLong());
        Task<T> task = keptTask(receipt, owner);
        if (task == null) {
            task = new Task<>(this, owner, receipt, generation, ownerGeneration,
                    compute, apply, onDrop, onComputedDrop, stampMicros);
            if (receipt != null) {
                receipt.task = task;
            }
        } else {
            task.load(generation, ownerGeneration, compute, apply, onDrop, onComputedDrop,
                    stampMicros);
        }
        submitted.incrementAndGet();
        try {
            executor.execute(task);
        } catch (Throwable rejectedExecution) {
            // Catches execute() failures such as OutOfMemory; only a successful claim-back counts as refused.
            if (task.claim(Task.QUEUED)) {
                submitted.decrementAndGet();
                boolean dequeued = executor.remove(task);
                task.endUnrun(dequeued);
                return refuse();
            }
        }
        // task.refused is set by MarkRefused elsewhere; this only reads it.
        if (task.refused) {
            submitted.decrementAndGet();
            task.endUnrun(true);
            return refuse();
        }
        if (submitRefusalReported.get()) {
            submitRefusalReported.set(false);
        }
        return true;
    }
    // Counts the refusal and reports it once per episode.
    private boolean refuse() {
        rejected.incrementAndGet();
        reportSubmitRefusalOnce();
        return false;
    }
    // Reads isShutdown to distinguish a full queue from a stopped pool.
    private void reportSubmitRefusalOnce() {
        if (!submitRefusalReported.compareAndSet(false, true)) {
            return;
        }
        String cause = executor.isShutdown()
                ? " The pool has been stopped."
                        + " It takes no more work."
                : " Its queue waiting for a worker is full."
                        + " Raise the"
                        + " queue capacity setting, give the pool more workers, or submit"
                        + " less.";
        CoreLog.error("Sandpaper's work pool refused a piece of work;"
                + " it was never run." + cause, null);
    }
    // Runs on the cancelling thread only; call only where removal is proven.
    private static void markDroppedUnrun(Runnable task) {
        if (task instanceof Task<?> owned && owned.receipt() != null) {
            owned.receipt().droppedUnrun = true;
        }
    }
    // Truncates nanos to 32-bit micros; spans stay exact to 1us despite wraparound.
    private static int micros(long nanos) {
        return (int) (nanos / 1_000L);
    }
    // Clamped to zero for a backward clock or a span over 35 minutes.
    private static long span(int microsDifference) {
        return microsDifference > 0 ? microsDifference : 0L;
    }
    // True if an apply place is taken or a drop callback waits; not that an entry is present.
    public boolean hasPending() {
        return awaiting.get() > 0 || !droppedCallbacks.isEmpty();
    }
    // Runs the first registered hook after each result or drop callback is queued, on the
    // thread that queued it: a worker, or whatever drains, cancels or closes this pool.
    // The hook must not drain, apply, submit, block or lock.
    public void wakeOnResult(Runnable hook) {
        if (hook == null) {
            onResult = null;
            return;
        }
        Runnable held = onResult;
        if (held != null && held != hook) {
            reportSecondWakeHook(held, hook);
            return;
        }
        onResult = hook;
    }
    // Reports a second wakeOnResult registration once per pool.
    private void reportSecondWakeHook(Runnable held, Runnable offered) {
        if (!secondWakeHookReported.compareAndSet(false, true)) {
            return;
        }
        CoreLog.error("Sandpaper's WorkPool.wakeOnResult was called a second time, by "
                + offered.getClass().getName() + ", while " + held.getClass().getName()
                + " already held the slot."
                + " The first registration is kept; this one is ignored."
                + " Give your caller its own WorkPool.",
                new IllegalStateException("second WorkPool.wakeOnResult from "
                        + offered.getClass().getName()));
    }
    private void wake() {
        Runnable hook = onResult;
        if (hook == null) {
            return;
        }
        try {
            hook.run();
        } catch (Throwable crashed) {
            reportWakeHookFailureOnce(crashed);
        }
    }
    // Reports a wake hook failure once per pool.
    private void reportWakeHookFailureOnce(Throwable crashed) {
        if (!wakeHookFailureReported.compareAndSet(false, true)) {
            return;
        }
        CoreLog.error("Sandpaper's work pool wake hook threw."
                + " A paused drain may stay paused."
                + " Fix the caller named in the trace.",
                crashed);
    }
    // Increments unconditionally without a retry loop; transient over-cap is allowed.
    private boolean reserveApplySlot() {
        if (awaiting.getAndIncrement() < completedCapacity) {
            return true;
        }
        awaiting.decrementAndGet();
        return false;
    }
    private void reportOverflowOnce() {
        if (!overflowReported.compareAndSet(false, true)) {
            return;
        }
        CoreLog.error("Sandpaper's work pool is holding its cap of " + completedCapacity
                + " finished result(s); this one is dropped."
                + " Raise the queue capacity setting, raise results applied per tick, or"
                + " submit less.", null);
    }
    private void reportApplyFailureOnce(Throwable crashed) {
        if (!applyFailureReported.compareAndSet(false, true)) {
            return;
        }
        CoreLog.error("A Sandpaper work pool result could not be applied; the consumer threw."
                + " The result is dropped."
                + " Fix the consumer named in the trace.",
                crashed);
    }
    // Reports a drainer thread violation without throwing.
    private void captureOrReportDrainer() {
        Thread owner = drainer.get();
        Thread caller = Thread.currentThread();
        if (owner == caller) {
            return;
        }
        if (tookTheDrain(owner, caller)) {
            return;
        }
        if (!offThreadDrainReported.compareAndSet(false, true)) {
            return;
        }
        Thread holder = drainer.get();
        CoreLog.error("Sandpaper's WorkPool.drainCompleted was called from thread '"
                + caller.getName() + "', already drained by '"
                + holder.getName() + "'."
                + " Drain from one thread,"
                + " or give this caller its own WorkPool.",
                new IllegalStateException("off-thread WorkPool.drainCompleted from "
                        + caller.getName()));
    }
    // True when the caller takes the drain: no thread held it, or the one seen holding it has ended
    boolean tookTheDrain(Thread seen, Thread caller) {
        if (seen != null && seen.isAlive()) {
            return false;
        }
        return drainer.compareAndSet(seen, caller);
    }
    private int drainDroppedCallbacks(int maxItems) {
        int ran = 0;
        while (ran < maxItems) {
            Task<?> dropped = droppedCallbacks.poll();
            if (dropped == null) {
                break;
            }
            ran++;
            try {
                dropped.runDrop();
            } catch (Throwable crashed) {
                CoreLog.error("Work pool drop callback failed", crashed);
            }
        }
        return ran;
    }
    // Applies up to maxItems results; returns count landed, not attempted; failures skip.
    public int drainCompleted(int maxItems) {
        captureOrReportDrainer();
        int droppedHere = drainDroppedCallbacks(maxItems);
        int landed = 0;
        int attempted = 0;
        long staleHere = 0L;
        long failedHere = 0L;
        int skipped = 0;
        long applyWaitHere = 0L;
        int nowMicros = 0;
        boolean clockRead = false;
        try {
            while (attempted < maxItems - droppedHere && skipped < MAX_SKIPPED_POLLS_PER_DRAIN) {
                Task<?> c = completed.poll();
                if (c == null) {
                    if (overflowReported.get()) {
                        overflowReported.set(false);
                    }
                    if (applyFailureReported.get()) {
                        applyFailureReported.set(false);
                    }
                    break;
                }
                if (!c.claim(Task.FINISHED)) {
                    // Already claimed elsewhere.
                    skipped++;
                    continue;
                }
                awaiting.decrementAndGet();
                if (c.generation != epoch.get()
                        || c.owner.generation.get() != c.ownerGeneration) {
                    c.droppedWithValue();
                    staleHere++;
                    skipped++;
                    continue;
                }
                // Apply wait: elapsed since this entry arrived in the completed queue.
                if (!clockRead) {
                    nowMicros = micros(clock.getAsLong());
                    clockRead = true;
                }
                int waited = nowMicros - c.stampMicros;
                if (waited < 0) {
                    nowMicros = micros(clock.getAsLong());
                    waited = nowMicros - c.stampMicros;
                }
                applyWaitHere += span(waited);
                // attempted counts every try, not only successes.
                attempted++;
                try {
                    c.apply();
                    landed++;
                } catch (Throwable crashed) {
                    failedHere++;
                    reportApplyFailureOnce(crashed);
                } finally {
                    c.release();
                    c.endReceipt();
                }
            }
        } finally {
            if (staleHere != 0L) {
                discarded.addAndGet(staleHere);
            }
            if (landed != 0) {
                applied.addAndGet(landed);
            }
            if (failedHere != 0L) {
                failed.addAndGet(failedHere);
            }
            if (attempted != 0) {
                applyAttempts.addAndGet(attempted);
            }
            if (applyWaitHere != 0L) {
                applyWaitMicros.addAndGet(applyWaitHere);
            }
        }
        return landed;
    }
    // Discards queued and unapplied work for every owner; running work finishes, but its result is dropped.
    public int cancelAll() {
        epoch.incrementAndGet();
        ClaimQueued everybody = new ClaimQueued(null);
        executor.getQueue().removeIf(everybody);
        if (everybody.queuedCallback) {
            wake();
        }
        int neverRan = everybody.claimed;
        discarded.addAndGet(neverRan);
        cancelledBeforeRunning.addAndGet(neverRan);
        int unapplied = pollClaimAndReleaseAll();
        awaiting.addAndGet(-unapplied);
        discarded.addAndGet(unapplied);
        return neverRan + unapplied;
    }
    // Claims and drops all completed entries; caller must adjust totals.
    private int pollClaimAndReleaseAll() {
        int unapplied = 0;
        for (Task<?> waiting = completed.poll(); waiting != null;
                waiting = completed.poll()) {
            if (waiting.claim(Task.FINISHED)) {
                waiting.droppedWithValue();
                unapplied++;
            }
        }
        return unapplied;
    }
    // Discards one owner's queued, in-flight and unapplied work; running work excluded.
    public int cancelAllFor(String owner) {
        if (owner == null || owner.isBlank()) {
            // Blank owner matches nothing; use cancelAll to discard everything.
            return 0;
        }
        Owner target = ownerFor(owner);
        target.generation.incrementAndGet();
        int dropped = 0;
        ClaimQueued queued = new ClaimQueued(target);
        executor.getQueue().removeIf(queued);
        if (queued.queuedCallback) {
            wake();
        }
        dropped += queued.claimed;
        cancelledBeforeRunning.addAndGet(dropped);
        ClaimFinished finished = new ClaimFinished(target);
        completed.removeIf(finished);
        int swept = finished.claimed;
        awaiting.addAndGet(-swept);
        dropped += swept;
        discarded.addAndGet(dropped);
        return dropped;
    }
    // Snapshot of thread counts and timing totals; awaitingApply read is atomic.
    public Stats stats() {
        return new Stats(executor.getCorePoolSize(), executor.getPoolSize(),
                executor.getLargestPoolSize(), executor.getActiveCount(),
                executor.getQueue().size(), awaiting.get(),
                submitted.get(), finished.get(), failed.get(), applied.get(),
                rejected.get(), discarded.get(),
                queueWaitMicros.get(), computeMicros.get(), applyWaitMicros.get(),
                applyAttempts.get());
    }
    @Override
    public void close() {
        closeWithin(SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS);
    }
    // Stops gracefully; finished work is not applied.
    // Returns false if work may still be running, since honoring an interrupt is up to the work itself.
    public boolean closeWithin(long timeout, TimeUnit unit) {
        epoch.incrementAndGet();
        executor.shutdown();
        boolean quiet;
        try {
            quiet = executor.awaitTermination(timeout, unit);
        } catch (InterruptedException cutShort) {
            // Restores the interrupt flag; treated the same as a timeout.
            Thread.currentThread().interrupt();
            quiet = false;
        }
        long unfinished = 0L;
        int neverStarted = 0;
        long interrupted = 0L;
        if (!quiet) {
            unfinished = Math.max(0L,
                    submitted.get() - finished.get() - cancelledBeforeRunning.get());
            List<Runnable> abandoned = executor.shutdownNow();
            for (Runnable task : abandoned) {
                if (task instanceof Task<?> owned && owned.claim(Task.QUEUED)) {
                    markDroppedUnrun(task);
                    owned.inExecutor = false;
                    owned.dropped();
                    neverStarted++;
                }
            }
            cancelledBeforeRunning.addAndGet(neverStarted);
            interrupted = Math.max(0L, unfinished - neverStarted);
        }
        int unapplied = pollClaimAndReleaseAll();
        awaiting.addAndGet(-unapplied);
        discarded.addAndGet((long) neverStarted + unapplied);
        if (!quiet) {
            CoreLog.error("Sandpaper's work pool was still busy "
                    + unit.toMillis(timeout) + " ms after being asked to stop; stopped anyway."
                    + " As many as " + unfinished + " task(s) had not finished,"
                    + " " + neverStarted + " of which had not started."
                    + " The other " + interrupted + " had started and"
                    + " got an interrupt: a request and not a stop, and may still finish.", null);
        }
        return quiet;
    }
    // Pool statistics: thread counts and timing totals, in microseconds.
    public record Stats(int configuredThreads, int liveThreads, int peakThreads,
                        int active, int queued, int awaitingApply,
                        long submitted, long finished, long failed, long applied,
                        long rejected, long discarded,
                        long queueWaitMicros, long computeMicros, long applyWaitMicros,
                        long applyAttempts) {
        // Formats thread counts for display; the three figures only make sense together.
        public String threadsLine() {
            if (liveThreads > 0) {
                return liveThreads + "/" + configuredThreads + " threads";
            }
            return peakThreads > 0
                    ? "0/" + configuredThreads + " threads resting"
                    : "0/" + configuredThreads + " threads never started";
        }
    }
}
