package dev.sandpaper.core;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.IntBinaryOperator;
import java.util.function.LongSupplier;
// Cooperative frame budget scheduler; register and pump from one thread.
public final class Scheduler {
    // The only writer of pumper; takes it from null or from a thread that has ended.
    private static final AtomicReferenceFieldUpdater<Scheduler, Thread> PUMPER =
            AtomicReferenceFieldUpdater.newUpdater(Scheduler.class, Thread.class, "pumper");
    private static final IntBinaryOperator WITH_BIT = (said, bit) -> said | bit;
    private final LongSupplier clock;
    private final LaneState[] lanes;
    private final ByUrgency byUrgency = new ByUrgency();
    private static final class ByUrgency implements Comparator<Entry> {
        long now;
        @Override
        public int compare(Entry a, Entry b) {
            boolean starvedA = a.starved(now);
            boolean starvedB = b.starved(now);
            if (starvedA != starvedB) {
                return starvedA ? -1 : 1;
            }
            // Inside the starved set, lateness decides ahead of priority.
            if (starvedA) {
                long overdueA = (now - a.dueSinceNanos) - a.starvationDeadlineNanos;
                long overdueB = (now - b.dueSinceNanos) - b.starvationDeadlineNanos;
                if (overdueA != overdueB) {
                    return Long.compare(overdueB, overdueA);
                }
            }
            int byWeight = Integer.compare(b.priorityWeight, a.priorityWeight);
            if (byWeight != 0) {
                return byWeight;
            }
            return Long.compare(a.dueSinceNanos, b.dueSinceNanos);
        }
    }
    // Also read off the pumping thread.
    private volatile int registrationCount;
    // Registrations so far, from any thread; never decreases, even when a job is cancelled.
    public int registrations() {
        return registrationCount;
    }
    // The thread currently pumping this scheduler.
    private volatile Thread pumper;
    // Each off-thread structural call gets a report-once key.
    private enum Call {
        REGISTER("register"),
        REGISTER_SLICED("registerSliced"),
        CANCEL_ALL_FOR("cancelAllFor"),
        CANCEL_ALL("cancelAll"),
        STATS("stats"),
        COUNTER_READ("the Handle counters"),
        RETIME("the Handle retimes");
        final String method;
        Call(String method) {
            this.method = method;
        }
    }
    // One bit per Call, tracking which violations were already reported.
    private final AtomicInteger reportedOffThread = new AtomicInteger();
    // Reports once per method per scheduler.
    private void reportIfOffThread(Call call) {
        Thread owner = pumper;
        Thread caller = Thread.currentThread();
        if (owner == null || owner == caller) {
            return;
        }
        int bit = 1 << call.ordinal();
        if ((reportedOffThread.getAndAccumulate(bit, WITH_BIT) & bit) != 0) {
            return;
        }
        CoreLog.error("Sandpaper's Scheduler." + call.method + " was called from thread '"
                + caller.getName() + "', but this Scheduler is pumped by '"
                + owner.getName() + "'. Use Sandpaper's WorkPool for work off the"
                + " pumping thread, or give this caller its own Scheduler pumped by"
                + " its own thread."
                + " Reported once each; the"
                + " other " + (Call.values().length - 1) + " checks still report.",
                new IllegalStateException("off-thread Scheduler." + call.method
                        + " from " + caller.getName()));
    }
    private void reportIfOffThreadCounterRead(String accessor) {
        Thread owner = pumper;
        Thread caller = Thread.currentThread();
        if (owner == null || owner == caller) {
            return;
        }
        int bit = 1 << Call.COUNTER_READ.ordinal();
        if ((reportedOffThread.getAndAccumulate(bit, WITH_BIT) & bit) != 0) {
            return;
        }
        CoreLog.error("Sandpaper's Handle." + accessor + "() was called from thread '"
                + caller.getName() + "', but the Scheduler behind this Handle is pumped by"
                + " '" + owner.getName() + "'. The value may be stale or torn,"
                + " and is still wrong."
                + " Read the counters on the pumping thread."
                + " Reported once for "
                + Call.COUNTER_READ.method + ".",
                new IllegalStateException("off-thread Handle." + accessor + " from "
                        + caller.getName()));
    }
    // Retimes write plain fields the pump reads; reported once per scheduler.
    private void reportIfOffThreadRetime(String method) {
        Thread owner = pumper;
        Thread caller = Thread.currentThread();
        if (owner == null || owner == caller) {
            return;
        }
        int bit = 1 << Call.RETIME.ordinal();
        if ((reportedOffThread.getAndAccumulate(bit, WITH_BIT) & bit) != 0) {
            return;
        }
        CoreLog.error("Sandpaper's Handle." + method + "() was called from thread '"
                + caller.getName() + "', but the Scheduler behind this Handle is pumped by"
                + " '" + owner.getName() + "'. Retime from a job on the pumping thread;"
                + " use Handle.expedite from another thread."
                + " Reported once for "
                + Call.RETIME.method + ".",
                new IllegalStateException("off-thread Handle." + method + " from "
                        + caller.getName()));
    }
    public Scheduler(LongSupplier nanoClock) {
        this.clock = nanoClock;
        Lane[] all = Lane.values();
        this.lanes = new LaneState[all.length];
        for (int i = 0; i < all.length; i++) {
            lanes[i] = new LaneState(this, nanoClock);
        }
    }
    // Registers periodic work and returns a Handle to control it; call from the pumping thread.
    public Handle register(JobSpec spec, Job job) {
        reportIfOffThread(Call.REGISTER);
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(job, "job");
        Entry e = newEntry(spec, true);
        e.job = job;
        e.lane.periodic.add(e);
        if (spec.hasDeadline() && !e.mustRun) {
            e.lane.deadlined++;
        }
        e.lane.rescan = true;
        return e;
    }
    // Registers work drained from budget that periodic jobs leave; call from the pumping thread.
    public Handle registerSliced(JobSpec spec, SlicedJob job) {
        reportIfOffThread(Call.REGISTER_SLICED);
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(job, "job");
        Entry e = newEntry(spec, false);
        e.slicedJob = job;
        e.lane.sliced.add(e);
        e.lane.slicedWakeNanos = Long.MIN_VALUE;
        return e;
    }
    // Cancels every job under one owner, on every lane; a null or blank owner matches nothing.
    public int cancelAllFor(String owner) {
        reportIfOffThread(Call.CANCEL_ALL_FOR);
        if (owner == null || owner.isBlank()) {
            return 0;
        }
        int stopped = 0;
        for (LaneState state : lanes) {
            stopped += cancelIn(state.periodic, owner);
            stopped += cancelIn(state.sliced, owner);
        }
        return stopped;
    }
    private static int cancelIn(ArrayList<Entry> entries, String owner) {
        int stopped = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (!e.cancelled && owner.equals(e.spec.owner())) {
                e.cancel();
                stopped++;
            }
        }
        return stopped;
    }
    // Cancels every job; call only on a scheduler you own, not the shared one.
    public int cancelAll() {
        reportIfOffThread(Call.CANCEL_ALL);
        int stopped = 0;
        for (LaneState state : lanes) {
            stopped += cancelIn(state.periodic);
            stopped += cancelIn(state.sliced);
            releaseCancelled(state.periodic);
            releaseCancelled(state.sliced);
        }
        return stopped;
    }
    private static int cancelIn(ArrayList<Entry> entries) {
        int stopped = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (!e.cancelled) {
                e.cancel();
                stopped++;
            }
        }
        return stopped;
    }
    private static void releaseCancelled(ArrayList<Entry> entries) {
        for (int i = 0, ends = entries.size(); i < ends; i++) {
            Entry e = entries.get(i);
            if (e.cancelled && !e.released) {
                e.job = null;
                e.slicedJob = null;
                e.released = true;
            }
        }
    }
    private Entry newEntry(JobSpec spec, boolean spreadAcrossPumps) {
        long now = clock.getAsLong();
        int index = registrationCount++;
        LaneState state = lanes[spec.lane().ordinal()];
        Entry e = new Entry(spec, index, state, !spreadAcrossPumps);
        e.registeredAtNanos = now;
        if (spec.timeBased()) {
            int phase = spreadAcrossPumps ? state.claimTimePhase(spec.periodNanos()) : 0;
            e.nextDueNanos = plus(now, Stagger.forNanos(phase, spec.periodNanos()));
        } else {
            long firstPump = state.pumpCount + 1;
            long phaseIndex = spreadAcrossPumps
                    ? state.claimPhase(spec.periodPumps(), firstPump) - firstPump
                    : 0L;
            e.nextDuePump = firstPump + Stagger.forPumps(phaseIndex, spec.periodPumps());
        }
        return e;
    }
    public void pump(Lane lane, long budgetNanos) {
        Thread caller = Thread.currentThread();
        Thread owner = pumper;
        if (owner != caller && (owner == null || !owner.isAlive())) {
            // Retaken only when the current owner is null or has ended.
            PUMPER.compareAndSet(this, owner, caller);
        }
        LaneState state = lanes[lane.ordinal()];
        long pumpCount = ++state.pumpCount;
        long now = clock.getAsLong();
        int swept = state.cancellations.get();
        if (swept > 0) {
            releaseCancelled(state.periodic);
            releaseCancelled(state.sliced);
            state.periodic.removeIf(e -> e.released);
            state.sliced.removeIf(e -> e.released);
            state.cancellations.addAndGet(-swept);
        }
        ArrayList<Entry> deferrable = state.deferrable;
        deferrable.clear();
        // Skips the periodic scan unless a watermark or a forced rescan says otherwise.
        boolean scan = state.rescan;
        // Expedite reuses this flag instead of its own.
        boolean expediting = scan;
        if (scan) {
            state.rescan = false;
        }
        scan |= pumpCount >= state.wakePump || now >= state.wakeNanos;
        if (expediting) {
            armExpedited(state, pumpCount, now);
        }
        long wakePump = Long.MAX_VALUE;
        long wakeNanos = Long.MAX_VALUE;
        // Size read once; jobs registered during this loop are not visited this pump.
        long mustRunSpent = 0L;
        int count = scan ? state.periodic.size() : 0;
        for (int i = 0; i < count; i++) {
            Entry e = state.periodic.get(i);
            // No write on the idle path.
            if (e.cancelled || e.paused) {
                continue;
            }
            markDue(e, pumpCount, now);
            if (!e.due) {
                // Local watermark, read once instead of the volatile fields repeatedly.
                if (e.timeBased) {
                    if (e.nextDueNanos < wakeNanos) {
                        wakeNanos = e.nextDueNanos;
                    }
                } else if (e.nextDuePump < wakePump) {
                    wakePump = e.nextDuePump;
                }
                continue;
            }
            if (e.mustRun) {
                long wasAt = now;
                now = run(e, state, now, budgetNanos - mustRunSpent);
                mustRunSpent += now - wasAt;
                wakePump = Math.min(wakePump, wakePumpOf(e, pumpCount));
                wakeNanos = Math.min(wakeNanos, wakeNanosOf(e));
            } else {
                deferrable.add(e);
            }
        }
        if (deferrable.size() > 1) {
            byUrgency.now = now;
            deferrable.sort(byUrgency);
        }
        // Zero; mustRun work is outside the budget.
        long budgetSpent = 0L;
        boolean ranOne = false;
        for (int i = 0, ends = deferrable.size(); i < ends; i++) {
            Entry e = deferrable.get(i);
            // All three checks are needed; another job running this pump can change due.
            if (e.cancelled || e.paused || !e.due) {
                continue;
            }
            if (ranOne && budgetSpent >= budgetNanos) {
                e.deferrals++;
                state.deferred++;
                continue;
            }
            long before = clock.getAsLong();
            long ended = run(e, state, before, budgetNanos - budgetSpent);
            budgetSpent += ended - before;
            ranOne = true;
        }
        // Must run after the drain and before the fold below.
        budgetSpent = escalateExpired(state, deferrable, budgetNanos, budgetSpent);
        // Folds deferrable entries into the watermark for the next pump.
        for (int i = 0, ends = deferrable.size(); i < ends; i++) {
            Entry e = deferrable.get(i);
            wakePump = Math.min(wakePump, wakePumpOf(e, pumpCount));
            wakeNanos = Math.min(wakeNanos, wakeNanosOf(e));
        }
        if (scan) {
            state.wakePump = wakePump;
            state.wakeNanos = wakeNanos;
        }
        drainSliced(state, budgetNanos - budgetSpent);
        reportCostsIfDue(lane, state);
    }
    // Turns every expedite request into due for the walk below.
    private static void armExpedited(LaneState state, long pumpCount, long now) {
        ArrayList<Entry> entries = state.periodic;
        for (int i = 0, ends = entries.size(); i < ends; i++) {
            Entry e = entries.get(i);
            if (!e.expedited) {
                continue;
            }
            e.expedited = false;
            if (e.cancelled || e.paused) {
                continue;
            }
            // The current instant, not a sentinel; due on the next walk.
            if (e.timeBased) {
                e.nextDueNanos = now;
            } else {
                e.nextDuePump = pumpCount;
            }
        }
    }
    // Earliest pump on which this entry can need the periodic walk.
    private static long wakePumpOf(Entry e, long pumpCount) {
        if (e.cancelled || e.paused) {
            return Long.MAX_VALUE;
        }
        if (e.due) {
            return pumpCount + 1L;
        }
        return e.timeBased ? Long.MAX_VALUE : e.nextDuePump;
    }
    // Earliest clock reading at which this entry can need the periodic walk.
    private static long wakeNanosOf(Entry e) {
        if (e.cancelled || e.paused || !e.timeBased || e.due) {
            return Long.MAX_VALUE;
        }
        return e.nextDueNanos;
    }
    private void drainSliced(LaneState state, long budget) {
        long remaining = budget;
        int size = state.sliced.size();
        int skipped = 0;
        int steps = 0;
        // Cached; state.pumpCount does not change before this pump ends.
        long pumpCount = state.pumpCount;
        int at = size > 0 ? Math.floorMod(state.slicedCursor, size) : 0;
        int advance = 0;
        while (remaining > 0L && size > 0 && steps < MAX_SLICE_STEPS_PER_PUMP) {
            Entry e = state.sliced.get(at);
            advance++;
            if (++at == size) {
                at = 0;
            }
            if (e.cancelled || e.paused || e.failedOnPump == pumpCount
                    || e.yieldedOnPump == pumpCount) {
                if (++skipped >= size) {
                    break;
                }
                continue;
            }
            skipped = 0;
            steps++;
            // Recomputed after each step, not a fixed opening budget.
            remaining -= step(e, state, remaining);
        }
        // Advances by entries visited, including skipped ones.
        state.slicedCursor += advance;
        freeChunkForStarvedSliced(state);
    }
    private long step(Entry e, LaneState state, long remainingNanos) {
        // Read here so catch and finally see the same value.
        long pumpCount = state.pumpCount;
        long before = clock.getAsLong();
        Step said;
        try {
            said = e.slicedJob.step(tickFor(e, before, pumpCount, remainingNanos));
            if (said == null) {
                // A null result is treated as YIELD, not MORE.
                said = Step.YIELD;
            }
            e.recordCleanRun(before);
        } catch (Throwable crashed) {
            e.failedOnPump = pumpCount;
            said = recordFailure(e, crashed) ? Step.DONE : Step.YIELD;
        }
        long cost = clock.getAsLong() - before;
        e.recordCost(cost, remainingNanos);
        e.lastRunNanos = before;
        e.lastRunPump = pumpCount;
        state.ran++;
        state.callbackNanos += cost;
        if (said == Step.DONE) {
            e.cancel();
        } else if (said == Step.YIELD) {
            e.yieldedOnPump = pumpCount;
        }
        return cost;
    }
    // Grants one step to the most overdue sliced job, outside the budget.
    private void freeChunkForStarvedSliced(LaneState state) {
        int size = state.sliced.size();
        if (size == 0) {
            return;
        }
        long now = clock.getAsLong();
        // Gate; nothing to grant before this watermark.
        if (now < state.slicedWakeNanos) {
            return;
        }
        Entry worst = null;
        long mostOverdue = 0L;
        long wake = Long.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            Entry e = state.sliced.get(i);
            // Cancelled is excluded permanently here, unlike the per-pump checks.
            if (e.cancelled) {
                continue;
            }
            if (e.paused) {
                continue;
            }
            // Uses last run, clean or not; the deferrable escalation road uses last clean run only.
            long since = e.lastRunPump == 0L ? e.registeredAtNanos : e.lastRunNanos;
            long deadline = e.spec.starvationNanos();
            // Cancelled and paused entries do not affect this watermark; the per-pump checks below still do.
            long eligibleAt = plus(since, deadline);
            if (eligibleAt < wake) {
                wake = eligibleAt;
            }
            if (e.lastRunPump == state.pumpCount || e.failedOnPump == state.pumpCount) {
                continue;
            }
            long overdue = overdueNanos(since, deadline, now);
            if (overdue == NOT_OVERDUE) {
                continue;
            }
            // Zero is a real value; null is the sentinel; a tie keeps the earlier entry.
            if (worst == null || overdue > mostOverdue) {
                mostOverdue = overdue;
                worst = e;
            }
        }
        if (worst == null) {
            state.slicedWakeNanos = wake;
            return;
        }
        state.freeChunks++;
        step(worst, state, 0L);
    }
    // Counts entries actually stepped, not pass-overs.
    private static final int MAX_SLICE_STEPS_PER_PUMP = 4096;
    // Negative sentinel; zero means due right now.
    private static final long NOT_OVERDUE = -1L;
    // Returns overdue magnitude, not raw wait time; both roads read it the same way.
    private static long overdueNanos(long sinceNanos, long deadlineNanos, long now) {
        long waited = now - sinceNanos;
        if (waited < deadlineNanos) {
            return NOT_OVERDUE;
        }
        return waited - deadlineNanos;
    }
    // Bounds the escalation drain rate, not a single spike.
    private static final int ESCALATION_DRAIN_PUMPS = 13;
    private static boolean escalates(Entry e) {
        if (!e.spec.hasDeadline() || e.mustRun) {
            return false;
        }
        return e.neverDrop || e.consecutiveEscalationOverruns < MAX_CONSECUTIVE_FAILURES;
    }
    // Overdue against the escalation deadline, measured from the last clean run.
    private static long escalationOverdue(Entry e, long now) {
        if (!escalates(e)) {
            return NOT_OVERDUE;
        }
        long since = e.cleanRuns == 0L ? e.registeredAtNanos : e.lastCleanRunNanos;
        return overdueNanos(since, e.spec.deadlineNanos(), now);
    }
    // Runs expired deferrable jobs outside the budget; the sliced equivalent of a free chunk.
    private long escalateExpired(LaneState state, ArrayList<Entry> deferrable,
                                 long budgetNanos, long spent) {
        if (state.deadlined == 0) {
            return spent;
        }
        int ends = deferrable.size();
        long now = clock.getAsLong();
        // Backlog is recomputed every pump, before any grants.
        int backlog = 0;
        for (int i = 0; i < ends; i++) {
            if (escalationOverdue(deferrable.get(i), now) != NOT_OVERDUE) {
                backlog++;
            }
        }
        if (backlog == 0) {
            return spent;
        }
        int grants = Math.max(1, backlog / ESCALATION_DRAIN_PUMPS);
        for (int granted = 0; granted < grants; granted++) {
            // Selects the worst by scanning, not sorting; the list is already sorted.
            Entry worst = null;
            long mostOverdue = 0L;
            for (int i = 0; i < ends; i++) {
                Entry e = deferrable.get(i);
                // Skips cancelled, paused, not due, or already run this pump.
                if (e.cancelled || e.paused || !e.due || e.lastRunPump == state.pumpCount) {
                    continue;
                }
                long overdue = escalationOverdue(e, now);
                if (overdue == NOT_OVERDUE) {
                    continue;
                }
                // Zero is a real value; null is the sentinel; a tie keeps the earlier entry.
                if (worst == null || overdue > mostOverdue) {
                    mostOverdue = overdue;
                    worst = e;
                }
            }
            if (worst == null) {
                // Stops when no candidate remains, even though the backlog count expected one.
                break;
            }
            // Reuses run's returned time as the next now, instead of reading the clock again.
            long wasAt = now;
            now = run(worst, state, now, budgetNanos - spent);
            long cost = now - wasAt;
            spent += cost;
            // Compares cost to the whole budget, not what remained.
            recordEscalation(worst, budgetNanos > 0L && cost > budgetNanos);
        }
        return spent;
    }
    // Counts escalations; reports once at the overrun cap.
    private void recordEscalation(Entry e, boolean tookTheLaneOverBudget) {
        if (!tookTheLaneOverBudget) {
            e.consecutiveEscalationOverruns = 0;
            return;
        }
        if (++e.consecutiveEscalationOverruns != MAX_CONSECUTIVE_FAILURES) {
            return;
        }
        CoreLog.error("Sandpaper job '" + e.spec.label() + "' reached its overrun cap on"
                + " lane " + e.spec.lane() + ". Its declared deadline of "
                + (e.spec.deadlineNanos() / 1_000_000L) + " ms was missed "
                + MAX_CONSECUTIVE_FAILURES + " times."
                + " "
                + (e.neverDrop
                        ? "Kept."
                                + " "
                        : "Escalation is now off, deferred as an ordinary job."
                                + " ")
                + "Optimise the code or re-slice it into smaller steps.", null);
    }
    private void markDue(Entry e, long pumpCount, long now) {
        long pausedAt = e.pausedAtNanos;
        if (pausedAt != Long.MIN_VALUE) {
            e.pausedAtNanos = Long.MIN_VALUE;
            long pausedForNanos = now - pausedAt;
            if (e.due) {
                e.dueSinceNanos = plus(e.dueSinceNanos, pausedForNanos);
            }
            e.pausedNanosSinceRun = plus(e.pausedNanosSinceRun, pausedForNanos);
        }
        if (e.due) {
            return;
        }
        boolean nowDue = e.timeBased ? now >= e.nextDueNanos : pumpCount >= e.nextDuePump;
        if (nowDue) {
            e.due = true;
            e.dueSinceNanos = now;
        }
    }
    // Returns the time after running.
    private long run(Entry e, LaneState state, long now, long remainingNanos) {
        long pumpCount = state.pumpCount;
        Tick tick = tickFor(e, now, pumpCount, remainingNanos);
        long before = clock.getAsLong();
        long ended;
        try {
            e.job.run(tick);
            e.recordCleanRun(now);
        } catch (Throwable crashed) {
            recordFailure(e, crashed);
        } finally {
            // One clock read serves as both the end time and the next start.
            ended = clock.getAsLong();
            e.recordCost(ended - before, remainingNanos);
            // Paired with state.ran; both count this callback together.
            state.callbackNanos += ended - before;
            e.due = false;
            e.dueSinceNanos = 0L;
            e.lastRunNanos = now;
            e.lastRunPump = pumpCount;
            // Reset here; the pause toll is not carried into the next run.
            e.pausedNanosSinceRun = 0L;
            if (e.timeBased) {
                e.nextDueNanos = plus(now, e.periodNanos);
            } else {
                e.nextDuePump = plus(pumpCount, e.periodPumps);
            }
            state.ran++;
        }
        return ended;
    }
    private boolean recordFailure(Entry e, Throwable crashed) {
        int strikes = ++e.consecutiveFailures;
        if (strikes < MAX_CONSECUTIVE_FAILURES) {
            CoreLog.error("Sandpaper job '" + e.spec.label() + "' threw on lane "
                    + e.spec.lane() + "; the rest of the pump carries on", crashed);
            return false;
        }
        if (e.neverDrop) {
            if (strikes == MAX_CONSECUTIVE_FAILURES) {
                CoreLog.error("Sandpaper job '" + e.spec.label() + "' threw " + strikes
                        + " times on lane " + e.spec.lane() + ". Kept; not reported again"
                        + " until it runs clean", crashed);
            }
            return false;
        }
        e.cancel();
        CoreLog.error("Sandpaper job '" + e.spec.label() + "' threw " + strikes
                + " times on lane " + e.spec.lane() + " and has been dropped", crashed);
        return true;
    }
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    // Saturating addition; pins at long ends, does not wrap.
    static long plus(long a, long b) {
        long sum = a + b;
        // Overflow when operands agree in sign and result disagrees.
        if (((a ^ sum) & (b ^ sum)) < 0) {
            return b < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return sum;
    }
    // Spreads starvation deadline by a 1.0 to 1.5 scale factor.
    static long jitteredStarvationNanos(long declaredNanos, int sequenceIndex) {
        return (long) (declaredNanos * (1.0 + Stagger.radicalInverseBase2(sequenceIndex) * 0.5));
    }
    private Tick tickFor(Entry e, long now, long pumpCount, long remainingNanos) {
        boolean first = e.lastRunPump == 0L;
        long since = first ? 0L : now - e.lastRunNanos - e.pausedNanosSinceRun;
        long pumps = first ? 0L : pumpCount - e.lastRunPump;
        boolean behind = e.timeBased
                ? since > e.lateAfterNanos
                : pumps > e.periodPumps;
        return new Tick(since, pumps, !first && behind, remainingNanos, e.cleanRuns);
    }
    // Lifetime totals; nothing resets them.
    public record LaneStats(long pumps, long ran, long deferred, int periodic, int sliced,
                           long freeChunks, long callbackNanos) {
    }
    public LaneStats stats(Lane lane) {
        reportIfOffThread(Call.STATS);
        LaneState s = lanes[lane.ordinal()];
        return new LaneStats(s.pumpCount, s.ran, s.deferred, s.periodic.size(), s.sliced.size(),
                s.freeChunks, s.callbackNanos);
    }
    private static final long COST_REPORT_PERIOD_NANOS = 5_000_000_000L;
    private static final int MAX_COST_ROWS_PER_LANE = 64;
    private void reportCostsIfDue(Lane lane, LaneState state) {
        if (!CoreLog.isVerbose()) {
            return;
        }
        long now = clock.getAsLong();
        if (now < state.nextCostReportNanos) {
            return;
        }
        state.nextCostReportNanos = plus(now, COST_REPORT_PERIOD_NANOS);
        // Lifetime counters, not just this reporting period.
        CoreLog.verbose(String.format(Locale.ROOT,
                "%s cost table: pump %d, %d periodic, %d sliced; lane totals ran %d,"
                        + " deferred %d, free chunks %d",
                lane, state.pumpCount, state.periodic.size(), state.sliced.size(),
                state.ran, state.deferred, state.freeChunks));
        int shown = reportCostRows(lane, state.periodic, false, 0);
        shown = reportCostRows(lane, state.sliced, true, shown);
        int entries = state.periodic.size() + state.sliced.size();
        if (shown < entries) {
            CoreLog.verbose(String.format(Locale.ROOT,
                    "%s cost table: %d further job(s) not listed; this table stops at %d"
                            + " rows a lane.",
                    lane, entries - shown, MAX_COST_ROWS_PER_LANE));
        }
    }
    private static int reportCostRows(Lane lane, ArrayList<Entry> entries, boolean sliced,
                                      int shown) {
        String kind = sliced ? "sliced" : "periodic";
        for (int i = 0; i < entries.size() && shown < MAX_COST_ROWS_PER_LANE; i++) {
            Entry e = entries.get(i);
            CoreLog.verbose(String.format(Locale.ROOT,
                    "%s #%d %s (%s) runs %d avg %dus peak %dus%s def %d over %d%s",
                    lane, e.index, e.costName, kind, e.runs,
                    e.costEmaNanos / 1_000L, e.peakCostNanos / 1_000L,
                    e.costDeadline,
                    e.deferrals, e.overruns, conditionOf(e)));
            shown++;
        }
        return shown;
    }
    // Owner is empty, never null, for unowned jobs.
    private static String nameOf(JobSpec spec) {
        return spec.owner().isEmpty() ? spec.label() : spec.owner() + "/" + spec.label();
    }
    // Milliseconds.
    private static String deadlineOf(JobSpec spec, boolean sliced, boolean mustRun) {
        if (sliced || mustRun) {
            return "";
        }
        return spec.hasDeadline()
                ? " dl " + (spec.deadlineNanos() / 1_000_000L) + "ms"
                : " dl NONE";
    }
    // Explains why counters are not moving; empty when the job is running.
    private static String conditionOf(Entry e) {
        if (e.cancelled) {
            return " CANCELLED";
        }
        return e.paused ? " PAUSED" : "";
    }
    private static final class LaneState {
        final ArrayList<Entry> periodic = new ArrayList<>(16);
        final ArrayList<Entry> sliced = new ArrayList<>(16);
        final ArrayList<Entry> deferrable = new ArrayList<>(16);
        final Map<Long, Long> nextPhase = new HashMap<>();
        final Map<Long, Integer> nextTimePhase = new HashMap<>();
        long pumpCount;
        long ran;
        // Lifetime total, used for overhead subtraction.
        long callbackNanos;
        long deferred;
        long freeChunks;
        int slicedCursor;
        // Only ever incremented; escalateExpired's gate for deferrable jobs with a deadline.
        int deadlined;
        // MIN_VALUE forces a scan; otherwise holds the minimum computed by the last walk.
        long wakePump = Long.MIN_VALUE;
        long wakeNanos = Long.MIN_VALUE;
        // MIN_VALUE forces a walk; a road that lowers it writes the sentinel, not a computed value.
        volatile long slicedWakeNanos = Long.MIN_VALUE;
        // Counts cancel calls, not cancelled entries; can exceed the entry count.
        final AtomicInteger cancellations = new AtomicInteger();
        // Volatile for Handle.setPaused off-thread; cleared before the walk, not after.
        volatile boolean rescan;
        // Zero until the first cost report; only reportCostsIfDue writes it.
        long nextCostReportNanos;
        // Final so setPeriodMillis can read it safely off thread.
        final LongSupplier clock;
        // Stored during construction; not called back into until later.
        final Scheduler owner;
        LaneState(Scheduler owner, LongSupplier clock) {
            this.owner = owner;
            this.clock = clock;
        }
        long claimPhase(long periodPumps, long firstPump) {
            long phase = nextPhase.getOrDefault(periodPumps, firstPump);
            nextPhase.put(periodPumps, phase + 1);
            return phase;
        }
        // Paired with claimPhase, but this one counts from zero.
        int claimTimePhase(long periodNanos) {
            int phase = nextTimePhase.getOrDefault(periodNanos, 0);
            nextTimePhase.put(periodNanos, phase + 1);
            return phase;
        }
    }
    private static final class Entry implements Handle {
        final JobSpec spec;
        // Read only through Handle.registrationIndex.
        final int index;
        // Final for safe access to the lane's pump count and clock.
        final LaneState lane;
        // Set to null on release; never read by Handle after that.
        Job job;
        SlicedJob slicedJob;
        // Fixed at registration; Handle.expedite reads it safely from any thread.
        final boolean sliced;
        // Volatile; written from any thread, read only when rescan is raised.
        volatile boolean expedited;
        // Pumping thread only; the sweep reads this field, not cancelled.
        boolean released;
        final boolean timeBased;
        final boolean mustRun;
        final boolean neverDrop;
        final int priorityWeight;
        // Retimeable copy on the entry; spec still decides the road.
        // The pump on which this job last yielded.
        long yieldedOnPump = -1L;
        volatile long periodPumps;
        volatile long periodNanos;
        // Derived from periodNanos; kept in sync whenever periodNanos changes.
        volatile long lateAfterNanos;
        long registeredAtNanos;
        long nextDuePump;
        long nextDueNanos;
        boolean due;
        long dueSinceNanos;
        // MIN_VALUE means not paused, or already tolled.
        volatile long pausedAtNanos = Long.MIN_VALUE;
        // Pause time since the last run; tickFor spends it, run resets it.
        long pausedNanosSinceRun;
        long lastRunNanos;
        long lastRunPump;
        // Last clean return, not last attempt; anchors the escalation deadline.
        long lastCleanRunNanos;
        long costEmaNanos;
        long deferrals;
        // Counts every run entered, including ones that threw; see cleanRuns.
        long runs;
        // Counts only runs that returned; see runs.
        long cleanRuns;
        long peakCostNanos;
        // Runs that went over budget; read together with deferrals.
        long overruns;
        int consecutiveFailures;
        // Consecutive over-budget escalations; resets when one fits the budget.
        int consecutiveEscalationOverruns;
        long failedOnPump;
        volatile boolean cancelled;
        volatile boolean paused;
        // Jittered escalation deadline; the sliced starvation road uses the unscaled one instead.
        final long starvationDeadlineNanos;
        // Feeds the cost table's name and deadline columns.
        private final String costName;
        private final String costDeadline;
        Entry(JobSpec spec, int index, LaneState lane, boolean sliced) {
            this.spec = spec;
            this.index = index;
            this.lane = lane;
            this.sliced = sliced;
            this.timeBased = spec.timeBased();
            this.mustRun = spec.isMustRun();
            this.neverDrop = spec.isNeverDropped();
            this.priorityWeight = spec.priority().weight();
            this.periodPumps = spec.periodPumps();
            this.periodNanos = spec.periodNanos();
            this.lateAfterNanos = plus(this.periodNanos, this.periodNanos / 2L);
            this.starvationDeadlineNanos =
                    jitteredStarvationNanos(spec.starvationNanos(), index);
            this.costName = nameOf(spec);
            this.costDeadline = deadlineOf(spec, sliced, mustRun);
        }
        boolean starved(long now) {
            return due && (now - dueSinceNanos) >= starvationDeadlineNanos;
        }
        void recordCleanRun(long at) {
            consecutiveFailures = 0;
            cleanRuns++;
            lastCleanRunNanos = at;
        }
        // given is the lane budget remaining; zero cannot be overspent.
        void recordCost(long nanos, long given) {
            // Skips zero cost; costEmaNanos == 0 is the first-sample sentinel.
            if (nanos > 0L) {
                costEmaNanos = costEmaNanos == 0L ? nanos : (costEmaNanos * 3 + nanos) >>> 2;
            }
            runs++;
            if (nanos > peakCostNanos) {
                peakCostNanos = nanos;
            }
            if (given > 0L && nanos > given) {
                overruns++;
            }
        }
        @Override
        public String label() {
            return spec.label();
        }
        @Override
        public boolean isCancelled() {
            return cancelled;
        }
        @Override
        public void cancel() {
            cancelled = true;
            // Flags first, then counts; cancel is idempotent.
            lane.cancellations.incrementAndGet();
        }
        @Override
        public void setPaused(boolean p) {
            if (p) {
                // Records the pause start; markDue uses it to toll the due time.
                pausedAtNanos = lane.clock.getAsLong();
            }
            paused = p;
            if (!p) {
                // Clears paused before raising rescan, never the other order.
                lane.rescan = true;
                // Sliced counterpart of the rescan flag; MIN_VALUE forces a walk.
                lane.slicedWakeNanos = Long.MIN_VALUE;
            }
        }
        @Override
        public boolean isPaused() {
            return paused;
        }
        @Override
        public void expedite() {
            if (sliced) {
                throw new IllegalStateException("Job '" + spec.label() + "' was registered"
                        + " through Scheduler.registerSliced. expedite has no effect on a"
                        + " sliced job."
                        + " Use Scheduler.register instead.");
            }
            // Both reads can race; armExpedited checks again before acting.
            if (cancelled || paused) {
                return;
            }
            expedited = true;
            // Sets expedited, then raises rescan to wake a sleeping lane.
            lane.rescan = true;
        }
        // Each reports an off-thread read before returning; reached through lane.owner.
        @Override
        public long averageCostNanos() {
            lane.owner.reportIfOffThreadCounterRead("averageCostNanos");
            return costEmaNanos;
        }
        @Override
        public long deferrals() {
            lane.owner.reportIfOffThreadCounterRead("deferrals");
            return deferrals;
        }
        @Override
        public long runs() {
            lane.owner.reportIfOffThreadCounterRead("runs");
            return runs;
        }
        @Override
        public long peakCostNanos() {
            lane.owner.reportIfOffThreadCounterRead("peakCostNanos");
            return peakCostNanos;
        }
        @Override
        public long overruns() {
            lane.owner.reportIfOffThreadCounterRead("overruns");
            return overruns;
        }
        @Override
        public void setPeriodMillis(long millis) {
            lane.owner.reportIfOffThreadRetime("setPeriodMillis");
            if (millis < 1) {
                throw new IllegalArgumentException("millis must be >= 1, was " + millis);
            }
            if (!spec.timeBased()) {
                throw new IllegalStateException("Job '" + spec.label() + "' counts pumps,"
                        + " not time. Use setPeriodPumps or"
                        + " JobSpec.everyMillis.");
            }
            // JobSpec.nanos handles the conversion and its overflow check.
            long wanted = JobSpec.nanos(millis, "millis");
            if (spec.hasDeadline() && wanted >= spec.deadlineNanos()) {
                wanted = spec.deadlineNanos() - 1L;
            }
            if (wanted == periodNanos) {
                return;
            }
            periodNanos = wanted;
            // Must run after periodNanos is set and before rearmNanos.
            lateAfterNanos = plus(wanted, wanted / 2L);
            rearmNanos(wanted);
        }
        @Override
        public void setPeriodPumps(long pumps) {
            lane.owner.reportIfOffThreadRetime("setPeriodPumps");
            if (pumps < 1) {
                throw new IllegalArgumentException("pumps must be >= 1, was " + pumps);
            }
            if (spec.timeBased()) {
                throw new IllegalStateException("Job '" + spec.label() + "' counts time,"
                        + " not pumps. Use setPeriodMillis or"
                        + " JobSpec.everyPumps.");
            }
            if (pumps == periodPumps) {
                return;
            }
            if (spec.hasDeadline()) {
                throw new IllegalStateException("Job '" + spec.label() + "' declared a"
                        + " deadline of " + (spec.deadlineNanos() / 1_000_000L) + " ms and"
                        + " counts pumps. Refused."
                        + " Register it without"
                        + " withDeadlineMillis.");
            }
            periodPumps = pumps;
            rearmPumps(pumps);
        }
        // One new period after the last run, or the first staggered run; keeps phase.
        private void rearmNanos(long period) {
            long now = lane.clock.getAsLong();
            long armed = lastRunPump == 0L ? nextDueNanos : plus(lastRunNanos, period);
            nextDueNanos = Math.min(armed, plus(now, period));
            standDownIfDeferred(nextDueNanos > now);
            // Retime can move next-due earlier than the watermark; forces a rescan.
            lane.rescan = true;
        }
        // Reads the live lane pump count, not a stale entry stamp.
        private void rearmPumps(long period) {
            long seen = lane.pumpCount;
            long armed = lastRunPump == 0L ? nextDuePump : plus(lastRunPump, period);
            nextDuePump = Math.min(armed, plus(seen, period));
            standDownIfDeferred(nextDuePump > seen);
            // Forces a rescan; retime moved the next-due field.
            lane.rescan = true;
        }
        // Clears the due mark only when retime genuinely pushed the job out.
        private void standDownIfDeferred(boolean deferred) {
            if (due && deferred) {
                due = false;
                dueSinceNanos = 0L;
            }
        }
        @Override
        public long periodMillis() {
            return periodNanos / 1_000_000L;
        }
        @Override
        public long periodPumps() {
            return periodPumps;
        }
        @Override
        public int registrationIndex() {
            return index;
        }
    }
}
