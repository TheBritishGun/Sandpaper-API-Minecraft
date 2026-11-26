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
    public static final long SHUTDOWN_GRACE_MILLIS = 1_000L;
    private static final int MAX_SKIPPED_POLLS_PER_DRAIN = 64;
    private static final class Task<T> implements Runnable {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int FINISHED = 2;
        private static final int DONE = 3;
        @SuppressWarnings("rawtypes")
        private static final AtomicIntegerFieldUpdater<Task> STATE =
                AtomicIntegerFieldUpdater.newUpdater(Task.class, "state");
        private final WorkPool pool;
        private final Owner owner;
        private final Receipt receipt;
        private long generation;
        private long ownerGeneration;
        private Consumer<T> apply;
        private Supplier<T> compute;
        private Runnable onDrop;
        private Consumer<T> onComputedDrop;
        private T value;
        private boolean refused;
        private volatile int state;
        private int stampMicros;
        private volatile boolean inExecutor;
        Task(WorkPool pool, Owner owner, Receipt receipt, long generation,
             long ownerGeneration, Supplier<T> compute, Consumer<T> apply,
             Runnable onDrop, Consumer<T> onComputedDrop, int stampMicros) {
            this.pool = pool;
            this.owner = owner;
            this.receipt = receipt;
            load(generation, ownerGeneration, compute, apply, onDrop, onComputedDrop, stampMicros);
        }
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
        boolean claim(int from) {
            return STATE.compareAndSet(this, from, DONE);
        }
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
        void endUnrun(boolean executorLetGo) {
            release();
            if (executorLetGo) {
                inExecutor = false;
            }
            endReceipt();
        }
        void endReceipt() {
            if (receipt != null) {
                receipt.ended = true;
            }
        }
        @Override
        public void run() {
            if (!STATE.compareAndSet(this, QUEUED, RUNNING)) {
                inExecutor = false;
                return;
            }
            int enteredMicros = micros(pool.clock.getAsLong());
            pool.queueWaitMicros.addAndGet(span(enteredMicros - stampMicros));
            Throwable crash = null;
            try {
                RenderCoreGuard.beforeTask();
                value = compute.get();
            } catch (Throwable crashed) {
                crash = crashed;
            } finally {
                compute = null;
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
    public static final class Receipt {
        private final AtomicBoolean spent = new AtomicBoolean();
        private volatile Task<?> task;
        private volatile boolean droppedUnrun;
        public boolean isDroppedUnrun() {
            return droppedUnrun;
        }
        private volatile boolean crashed;
        public boolean isCrashed() {
            return crashed;
        }
        private volatile boolean ended;
        public boolean isEnded() {
            return ended;
        }
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
    private final LongSupplier clock;
    private final Queue<Task<?>> completed = new ConcurrentLinkedQueue<>();
    private final Queue<Task<?>> droppedCallbacks = new ConcurrentLinkedQueue<>();
    private final int completedCapacity;
    private final int queueCapacity;
    private final AtomicInteger awaiting = new AtomicInteger();
    private final AtomicBoolean overflowReported = new AtomicBoolean();
    private final AtomicBoolean applyFailureReported = new AtomicBoolean();
    private final AtomicBoolean submitRefusalReported = new AtomicBoolean();
    private final AtomicReference<Thread> drainer = new AtomicReference<>();
    private final AtomicBoolean offThreadDrainReported = new AtomicBoolean();
    private volatile Runnable onResult;
    private final AtomicBoolean secondWakeHookReported = new AtomicBoolean();
    private final AtomicBoolean wakeHookFailureReported = new AtomicBoolean();
    private final AtomicLong cancelledBeforeRunning = new AtomicLong();
    private final AtomicLong epoch = new AtomicLong();
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
    private static final class ClaimQueued implements Predicate<Runnable> {
        private final Owner only;
        private int claimed;
        private boolean queuedCallback;
        ClaimQueued(Owner only) {
            this.only = only;
        }
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
    private final java.util.concurrent.ConcurrentHashMap<String, Owner> owners =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Owner unowned = new Owner("");
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong finished = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong applied = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong discarded = new AtomicLong();
    private final AtomicLong queueWaitMicros = new AtomicLong();
    private final AtomicLong computeMicros = new AtomicLong();
    private final AtomicLong applyWaitMicros = new AtomicLong();
    private final AtomicLong applyAttempts = new AtomicLong();
    public static int defaultThreads() {
        return Background.threads();
    }
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
    public <T> boolean submit(Supplier<T> compute, Consumer<T> apply, Receipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        return submitAs("", compute, apply, receipt, null, null);
    }
    public <T> boolean submit(String owner, Supplier<T> compute, Consumer<T> apply) {
        checkOwner(owner);
        return submitAs(owner, compute, apply, null, null, null);
    }
    public <T> boolean submit(String owner, Supplier<T> compute, Consumer<T> apply,
                              Receipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        checkOwner(owner);
        return submitAs(owner, compute, apply, receipt, null, null);
    }
    public <T> boolean submit(String owner, Supplier<T> compute, Consumer<T> apply,
                              Receipt receipt, Runnable onDrop, Consumer<T> onComputedDrop) {
        checkOwner(owner);
        return submitAs(owner, compute, apply, receipt,
                Objects.requireNonNull(onDrop, "onDrop"),
                Objects.requireNonNull(onComputedDrop, "onComputedDrop"));
    }
    private static void checkOwner(String owner) {
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
            if (task.claim(Task.QUEUED)) {
                submitted.decrementAndGet();
                boolean dequeued = executor.remove(task);
                task.endUnrun(dequeued);
                return refuse();
            }
        }
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
    private boolean refuse() {
        rejected.incrementAndGet();
        reportSubmitRefusalOnce();
        return false;
    }
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
    private static void markDroppedUnrun(Runnable task) {
        if (task instanceof Task<?> owned && owned.receipt() != null) {
            owned.receipt().droppedUnrun = true;
        }
    }
    private static int micros(long nanos) {
        return (int) (nanos / 1_000L);
    }
    private static long span(int microsDifference) {
        return microsDifference > 0 ? microsDifference : 0L;
    }
    public boolean hasPending() {
        return awaiting.get() > 0 || !droppedCallbacks.isEmpty();
    }
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
    private void reportWakeHookFailureOnce(Throwable crashed) {
        if (!wakeHookFailureReported.compareAndSet(false, true)) {
            return;
        }
        CoreLog.error("Sandpaper's work pool wake hook threw."
                + " A paused drain may stay paused."
                + " Fix the caller named in the trace.",
                crashed);
    }
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
    public int cancelAllFor(String owner) {
        if (owner == null || owner.isBlank()) {
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
    public boolean closeWithin(long timeout, TimeUnit unit) {
        epoch.incrementAndGet();
        executor.shutdown();
        boolean quiet;
        try {
            quiet = executor.awaitTermination(timeout, unit);
        } catch (InterruptedException cutShort) {
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
    public record Stats(int configuredThreads, int liveThreads, int peakThreads,
                        int active, int queued, int awaitingApply,
                        long submitted, long finished, long failed, long applied,
                        long rejected, long discarded,
                        long queueWaitMicros, long computeMicros, long applyWaitMicros,
                        long applyAttempts) {
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
