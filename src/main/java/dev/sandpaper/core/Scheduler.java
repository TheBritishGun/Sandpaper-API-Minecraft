package dev.sandpaper.core;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntBinaryOperator;
import java.util.function.LongSupplier;
// Cooperative frame budget scheduler; register and pump from one thread.
public final class Scheduler {
    private static final boolean FRAME_SPREAD = Boolean.getBoolean("sandpaper.frameSpread");
    private static final IntBinaryOperator WITH_BIT = (said, bit) -> said | bit;
    private static final boolean TICK_VALUES = Boolean.getBoolean("sandpaper.tickValues");
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
    private volatile int registrationCount;
    private long tickValuesEngaged = 0L;
    // Returns the registration count.
    public int registrations() {
        return registrationCount;
    }
    private volatile Thread pumper;
    private static final long LEGACY = 0L;
    private static final long ENROLLING = 1L;
    private static final long STRICT = 2L;
    private static final long PHASE_MASK = ENROLLING | STRICT;
    private static final int TICKET_SHIFT = 2;
    private static final long TICKET_UNIT = 1L << TICKET_SHIFT;
    private static final long MAX_FOREIGN_TICKETS = 0x3fff_ffffL;
    private static final long LEGACY_FULL = MAX_FOREIGN_TICKETS << TICKET_SHIFT;
    private static final long LEGACY_LAST_SLOT = LEGACY_FULL - TICKET_UNIT;
    private static final long LEGACY_ENTRY_GATE = LEGACY_FULL + TICKET_UNIT;
    private final AtomicLong admission = new AtomicLong();
    private final Object ownerClaim = new Object();
    private int ownerPumpDepth;
    private int ownerMutationDepth;
    private boolean ownerCallback;
    private boolean ownerEnrolling;
    private boolean ownerStrict;
    private int enrolledSpread;
    private long frameSpreadEngaged;
    private long frameSpreadLate;
    private long frameSpreadCompletedParts;

    private long takeLegacyTicket() {
        long word = admission.get();
        long phase = word & PHASE_MASK;
        if (phase != LEGACY) {
            return phase;
        }
        long ticketWord = word & ~LEGACY_ENTRY_GATE;
        if (ticketWord == LEGACY_FULL) {
            throw new IllegalStateException("Too many foreign Scheduler calls");
        }
        if ((word & LEGACY_ENTRY_GATE) == 0L || ticketWord == LEGACY_LAST_SLOT) {
            long witness = admission.compareAndExchange(word, word + TICKET_UNIT);
            if (witness == word) {
                return LEGACY;
            }
            phase = witness & PHASE_MASK;
            if (phase != LEGACY) {
                return phase;
            }
        }
        return takeContendedLegacyTicket();
    }

    private long takeContendedLegacyTicket() {
        synchronized (admission) {
            long phase = admission.get() & PHASE_MASK;
            if (phase != LEGACY) {
                return phase;
            }
            long word = admission.getAndAdd(LEGACY_ENTRY_GATE);
            phase = word & PHASE_MASK;
            if (phase != LEGACY) {
                return phase;
            }
            if (word == LEGACY_LAST_SLOT) {
                long gatedLastSlot = LEGACY_ENTRY_GATE + LEGACY_LAST_SLOT;
                long witness = admission.compareAndExchange(gatedLastSlot, LEGACY_FULL);
                if (witness == gatedLastSlot) {
                    return LEGACY;
                }
                word = witness - LEGACY_ENTRY_GATE;
            }
            if (word < LEGACY_LAST_SLOT) {
                admission.addAndGet(TICKET_UNIT - LEGACY_ENTRY_GATE);
                return LEGACY;
            }
            admission.addAndGet(-LEGACY_ENTRY_GATE);
        }
        throw new IllegalStateException("Too many foreign Scheduler calls");
    }

    private void leaveForeignCall() {
        admission.addAndGet(-TICKET_UNIT);
    }

    private void leaveOwnerCall(boolean pump) {
        if (pump) {
            ownerPumpDepth--;
        } else {
            ownerMutationDepth--;
        }
        if (ownerStrict && enrolledSpread == 0 && ownerPumpDepth == 0 && ownerMutationDepth == 0) {
            ownerStrict = false;
            admission.set(LEGACY);
        }
    }

    private void leaveCall(boolean ownerCall, boolean pump) {
        if (ownerCall) {
            leaveOwnerCall(pump);
        } else {
            leaveForeignCall();
        }
    }

    private boolean enterMutation(Call call) {
        if (pumper == Thread.currentThread()) {
            if (ownerEnrolling) {
                throw new IllegalStateException("Frame-spread enrollment is in progress");
            }
            ownerMutationDepth++;
            return true;
        }
        if (takeLegacyTicket() != LEGACY) {
            throw new IllegalStateException("Scheduler." + call.method
                    + " requires the frame-spread owner thread");
        }
        return false;
    }

    private boolean enterPump(Lane lane) {
        Thread caller = Thread.currentThread();
        if (pumper == caller) {
            if (ownerStrict || ownerEnrolling) {
                if (ownerPumpDepth != 0 || ownerMutationDepth != 0) {
                    throw new IllegalStateException("Frame-spread scheduler call is already in progress");
                }
                Objects.requireNonNull(lane, "lane");
            }
            ownerPumpDepth++;
            return true;
        }
        return enterForeignPump(caller, lane);
    }

    private boolean enterForeignPump(Thread caller, Lane lane) {
        long phase = takeLegacyTicket();
        if (phase == LEGACY) {
            return bindLegacyPumpOrKeepTicket(caller);
        }
        if (phase == STRICT) {
            Thread owner = pumper;
            if (owner != null && !owner.isAlive()) {
                synchronized (ownerClaim) {
                    owner = pumper;
                    boolean ownerTerminated = owner != null && !owner.isAlive();
                    phase = takeLegacyTicket();
                    if (phase == LEGACY) {
                        return bindLegacyPumpOrKeepTicket(caller);
                    }
                    if (phase == STRICT && ownerTerminated) {
                        Objects.requireNonNull(lane, "lane");
                        if (claimStrictOwner(caller, true)) {
                            return true;
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("Scheduler.pump requires the frame-spread owner thread");
    }

    private boolean bindLegacyPumpOrKeepTicket(Thread caller) {
        boolean keepTicket = false;
        try {
            Thread owner = pumper;
            if (owner == null || !owner.isAlive()) {
                synchronized (ownerClaim) {
                    owner = pumper;
                    if (owner == null || !owner.isAlive()) {
                        bindOwner(caller, true);
                        return true;
                    }
                }
            }
            keepTicket = true;
            return false;
        } finally {
            if (!keepTicket) {
                leaveForeignCall();
            }
        }
    }

    private boolean claimStrictOwner(Thread caller, boolean pump) {
        if ((admission.get() & PHASE_MASK) != STRICT) {
            return false;
        }
        admission.getAndAdd(TICKET_UNIT);
        try {
            bindOwner(caller, pump);
            return true;
        } finally {
            leaveForeignCall();
        }
    }

    private void bindOwner(Thread caller, boolean pump) {
        if (pump) {
            ownerPumpDepth++;
        } else {
            ownerMutationDepth++;
        }
        pumper = caller;
    }

    private static IllegalStateException spreadRegistrationRejected() {
        return new IllegalStateException("Scheduler.registerFrameSpread requires an exclusive owner thread");
    }

    // Returns true when this call reserved the first enrollment transition.
    private boolean enterSpreadRegistration(Thread caller) {
        if (pumper == caller) {
            if (ownerMutationDepth != 0 || ownerPumpDepth > 1
                    || (ownerPumpDepth == 1 && !ownerCallback)) {
                throw spreadRegistrationRejected();
            }
            if (ownerStrict) {
                ownerMutationDepth++;
                return false;
            }
            reserveFirstEnrollment();
        } else {
            synchronized (ownerClaim) {
                Thread owner = pumper;
                if (owner != null && owner.isAlive()) {
                    throw spreadRegistrationRejected();
                }
                if (owner != null && claimStrictOwner(caller, false)) {
                    return false;
                }
                reserveFirstEnrollment();
            }
        }
        return true;
    }

    private void reserveFirstEnrollment() {
        long word = admission.compareAndExchange(LEGACY, ENROLLING);
        if (word == LEGACY_ENTRY_GATE) {
            synchronized (admission) {
                word = admission.compareAndExchange(LEGACY, ENROLLING);
            }
        }
        if (word != LEGACY) {
            throw spreadRegistrationRejected();
        }
        ownerEnrolling = true;
        ownerMutationDepth++;
    }

    private void publishFirstEnrollment(Thread caller) {
        if (pumper != caller) {
            synchronized (ownerClaim) {
                pumper = caller;
            }
        }
        ownerStrict = true;
        admission.set(STRICT);
    }

    private boolean checkSpreadOwner(String method) {
        Thread owner = pumper;
        if (owner == null) {
            return false;
        }
        if (owner != Thread.currentThread()) {
            throw new IllegalStateException(method + " requires the frame-spread owner thread");
        }
        return true;
    }
    private enum Call {
        REGISTER("register"),
        REGISTER_SLICED("registerSliced"),
        REGISTER_VALUES("registerValues"),
        REGISTER_SLICED_VALUES("registerSlicedValues"),
        CANCEL_ALL_FOR("cancelAllFor"),
        CANCEL_ALL("cancelAll"),
        STATS("stats"),
        TICK_VALUES_ENGAGED("tickValuesEngaged"),
        COUNTER_READ("the Handle counters"),
        RETIME("the Handle retimes");
        final String method;
        Call(String method) {
            this.method = method;
        }
    }
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
    // Registers periodic work and returns its handle.
    public Handle register(JobSpec spec, Job job) {
        boolean ownerCall = enterMutation(Call.REGISTER);
        try {
            reportIfOffThread(Call.REGISTER);
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(job, "job");
            Entry e = newEntry(spec, true);
            e.job = job;
            return addPeriodic(e);
        } finally {
            leaveCall(ownerCall, false);
        }
    }
    // Registers periodic work with Tick values.
    public Handle registerValues(JobSpec spec, ValuesJob job) {
        boolean ownerCall = enterMutation(Call.REGISTER_VALUES);
        try {
            reportIfOffThread(Call.REGISTER_VALUES);
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(job, "job");
            Entry e = newEntry(spec, true);
            if (TICK_VALUES) {
                e.valuesJob = job;
            } else {
                e.job = tick -> job.run(tick.sinceLastRunNanos(), tick.pumpsSinceLastRun(),
                        tick.catchUp(), tick.remainingNanos(), tick.runIndex());
            }
            return addPeriodic(e);
        } finally {
            leaveCall(ownerCall, false);
        }
    }
    private static Handle addPeriodic(Entry e) {
        e.lane.periodic.add(e);
        if (e.spec.hasDeadline() && !e.mustRun) {
            e.lane.deadlined++;
        }
        e.lane.rescan = true;
        return e;
    }
    // Registers sliced work for leftover budget.
    public Handle registerSliced(JobSpec spec, SlicedJob job) {
        boolean ownerCall = enterMutation(Call.REGISTER_SLICED);
        try {
            reportIfOffThread(Call.REGISTER_SLICED);
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(job, "job");
            Entry e = newEntry(spec, false);
            e.slicedJob = job;
            return addSliced(e);
        } finally {
            leaveCall(ownerCall, false);
        }
    }
    // Registers sliced work with Tick values.
    public Handle registerSlicedValues(JobSpec spec, ValuesStep step) {
        boolean ownerCall = enterMutation(Call.REGISTER_SLICED_VALUES);
        try {
            reportIfOffThread(Call.REGISTER_SLICED_VALUES);
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(step, "step");
            Entry e = newEntry(spec, false);
            if (TICK_VALUES) {
                e.valuesStep = step;
            } else {
                e.slicedJob = tick -> step.step(tick.sinceLastRunNanos(), tick.pumpsSinceLastRun(),
                        tick.catchUp(), tick.remainingNanos(), tick.runIndex());
            }
            return addSliced(e);
        } finally {
            leaveCall(ownerCall, false);
        }
    }
    private static Handle addSliced(Entry e) {
        LaneState state = e.lane;
        if (state.listedSpread != 0 || state.parkedSpread != 0) {
            state.reserveSlicedCapacity(1);
        }
        state.sliced.add(e);
        state.slicedWakeNanos = Long.MIN_VALUE;
        return e;
    }
    /** Registers indexed parts on the spec's lane. */
    public FrameSpreadHandle registerFrameSpread(JobSpec spec, int parts, int maxPumps, FramePart work) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(work, "work");
        if (parts < 1 || maxPumps < 1) {
            throw new IllegalArgumentException("parts and maxPumps must be positive");
        }
        LaneState state = lanes[spec.lane().ordinal()];
        Thread caller = Thread.currentThread();
        boolean firstEnrollment = enterSpreadRegistration(caller);
        boolean published = false;
        try {
            long now = clock.getAsLong();
            int index = registrationCount;
            SpreadEntry spread = new SpreadEntry(spec, index, state, parts, maxPumps, work, now);
            state.reserveSlicedCapacity(2);
            state.appendSpread(spread);
            if (!FRAME_SPREAD) {
                state.slicedWakeNanos = Long.MIN_VALUE;
            }
            enrolledSpread++;
            registrationCount = index + 1;
            if (firstEnrollment) {
                publishFirstEnrollment(caller);
                published = true;
            }
            return spread;
        } finally {
            if (firstEnrollment) {
                ownerEnrolling = false;
            }
            leaveOwnerCall(false);
            if (firstEnrollment && !published) {
                admission.set(LEGACY);
            }
        }
    }

    // Cancels all jobs matching a name, on all lanes.
    public int cancelAllFor(String owner) {
        boolean ownerCall = enterMutation(Call.CANCEL_ALL_FOR);
        try {
            reportIfOffThread(Call.CANCEL_ALL_FOR);
            if (owner == null || owner.isBlank()) {
                return 0;
            }
            int stopped = 0;
            for (LaneState state : lanes) {
                stopped += cancelIn(state.periodic, owner);
                stopped += cancelIn(state.sliced, owner);
                stopped += cancelParked(state, owner);
            }
            return stopped;
        } finally {
            leaveCall(ownerCall, false);
        }
    }
    private static int cancelIn(ArrayList<Entry> entries, String owner) {
        int stopped = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e != null && !e.cancelled && owner.equals(e.spec.owner())) {
                e.cancel();
                stopped++;
            }
        }
        return stopped;
    }
    // Cancels every job on this scheduler.
    public int cancelAll() {
        boolean ownerCall = enterMutation(Call.CANCEL_ALL);
        try {
            reportIfOffThread(Call.CANCEL_ALL);
            int stopped = 0;
            for (LaneState state : lanes) {
                stopped += cancelIn(state.periodic);
                stopped += cancelIn(state.sliced);
                stopped += cancelParked(state, null);
                releaseCancelled(state.periodic);
                releaseCancelled(state.sliced);
                for (SpreadEntry e = state.parkedHead; e != null; e = e.parkedNext) {
                    releaseCancelled(e);
                }
            }
            return stopped;
        } finally {
            leaveCall(ownerCall, false);
        }
    }
    private static int cancelIn(ArrayList<Entry> entries) {
        int stopped = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e != null && !e.cancelled) {
                e.cancel();
                stopped++;
            }
        }
        return stopped;
    }
    private static int cancelParked(LaneState state, String owner) {
        int stopped = 0;
        for (SpreadEntry e = state.parkedHead; e != null; e = e.parkedNext) {
            if (!e.cancelled && (owner == null || owner.equals(e.spec.owner()))) {
                e.cancel();
                stopped++;
            }
        }
        return stopped;
    }

    private static void releaseCancelled(ArrayList<Entry> entries) {
        for (int i = 0, end = entries.size(); i < end; i++) {
            Entry e = entries.get(i);
            if (e != null) {
                releaseCancelled(e);
            }
        }
    }

    private static void releaseCancelled(Entry e) {
        if (e.cancelled && !e.released) {
            e.job = null;
            e.slicedJob = null;
            e.valuesJob = null;
            e.valuesStep = null;
            if (e instanceof SpreadEntry spread) {
                spread.work = null;
            }
            e.released = true;
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
        boolean ownerCall = enterPump(lane);
        try {
            LaneState state = lanes[lane.ordinal()];
            long pumpCount = ++state.pumpCount;
            try {
                admitFrameSpread(state);
                pumpLane(state, pumpCount, budgetNanos, ownerCall && ownerPumpDepth == 1);
            } finally {
                if (state.deadlineHead != null) {
                    finishFrameSpreadDeadlines(state);
                }
                if (state.slicedNeedsCompaction) {
                    compactSliced(state, false);
                }
            }
            reportCostsIfDue(lane, state);
        } finally {
            leaveCall(ownerCall, true);
        }
    }

    private void pumpLane(LaneState state, long pumpCount, long budgetNanos,
                          boolean markCallback) {
        long now = clock.getAsLong();
        int swept = state.cancellations.get();
        if (swept > 0) {
            releaseCancelled(state.periodic);
            releaseCancelled(state.sliced);
            state.periodic.removeIf(e -> e.released);
            sweepCancelledSliced(state);
            state.cancellations.addAndGet(-swept);
        }
        ArrayList<Entry> deferrable = state.deferrable;
        deferrable.clear();
        boolean scan = state.rescan;
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
        long mustRunSpent = 0L;
        int count = scan ? state.periodic.size() : 0;
        for (int i = 0; i < count; i++) {
            Entry e = state.periodic.get(i);
            if (e.cancelled || e.paused) {
                continue;
            }
            markDue(e, pumpCount, now);
            if (!e.due) {
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
                now = run(e, state, now, budgetNanos - mustRunSpent, markCallback);
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
        long budgetSpent = 0L;
        boolean ranOne = false;
        for (int i = 0, ends = deferrable.size(); i < ends; i++) {
            Entry e = deferrable.get(i);
            if (e.cancelled || e.paused || !e.due) {
                continue;
            }
            if (ranOne && budgetSpent >= budgetNanos) {
                e.deferrals++;
                state.deferred++;
                continue;
            }
            long before = clock.getAsLong();
            long ended = run(e, state, before, budgetNanos - budgetSpent, markCallback);
            budgetSpent += ended - before;
            ranOne = true;
        }
        budgetSpent = escalateExpired(state, deferrable, budgetNanos, budgetSpent, markCallback);
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
        drainSliced(state, budgetNanos - budgetSpent, markCallback);
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
            if (e.timeBased) {
                e.nextDueNanos = now;
            } else {
                e.nextDuePump = pumpCount;
            }
        }
    }
    // Earliest pump needing the periodic walk.
    private static long wakePumpOf(Entry e, long pumpCount) {
        if (e.cancelled || e.paused) {
            return Long.MAX_VALUE;
        }
        if (e.due) {
            return pumpCount + 1L;
        }
        return e.timeBased ? Long.MAX_VALUE : e.nextDuePump;
    }
    // Earliest clock time needing the periodic walk.
    private static long wakeNanosOf(Entry e) {
        if (e.cancelled || e.paused || !e.timeBased || e.due) {
            return Long.MAX_VALUE;
        }
        return e.nextDueNanos;
    }
    private static void admitFrameSpread(LaneState state) {
        if (state.listedSpread == 0) {
            return;
        }
        ArrayList<Entry> entries = state.sliced;
        for (int i = 0, end = entries.size(); i < end; i++) {
            if (!(entries.get(i) instanceof SpreadEntry e)) {
                continue;
            }
            if (!FRAME_SPREAD) {
                boolean wasReady = e.ready;
                e.ready = e.active() && !e.cancelled;
                if (e.ready && !wasReady) {
                    state.slicedWakeNanos = Long.MIN_VALUE;
                }
                continue;
            }
            e.ready = e.active() && !e.cancelled && !e.paused;
            if (!e.ready || e.eligiblePumps == e.maxPumps) {
                continue;
            }
            Scheduler owner = state.owner;
            if (e.eligiblePumps == 0) {
                owner.frameSpreadEngaged = plus(owner.frameSpreadEngaged, 1L);
            }
            int age = ++e.eligiblePumps;
            e.target = (int) (((long) age * e.parts + e.phase) / e.maxPumps);
            if (age == e.maxPumps) {
                if (state.deadlineTail == null) {
                    state.deadlineHead = e;
                } else {
                    state.deadlineTail.deadlineNext = e;
                }
                state.deadlineTail = e;
            }
        }
    }

    private void finishFrameSpreadDeadlines(LaneState state) {
        while (state.deadlineHead != null) {
            SpreadEntry e = state.deadlineHead;
            state.deadlineHead = e.deadlineNext;
            e.deadlineNext = null;
            if (e.active() && e.eligiblePumps == e.maxPumps && !e.cancelled) {
                e.late = true;
                frameSpreadLate = plus(frameSpreadLate, 1L);
                state.appendDebt(e);
            }
        }
        state.deadlineTail = null;
    }

    private void sweepCancelledSliced(LaneState state) {
        if (state.listedSpread == 0 && state.slicedTombstones == 0) {
            state.sliced.removeIf(e -> e.released);
        } else {
            compactSliced(state, true);
        }
        for (SpreadEntry e = state.parkedHead; e != null; ) {
            SpreadEntry next = e.parkedNext;
            if (e.cancelled) {
                releaseCancelled(e);
                state.unlinkParked(e);
                enrolledSpread--;
                state.slicedWakeNanos = Long.MIN_VALUE;
            }
            e = next;
        }
    }

    // Next retained entry after the old cursor.
    private void compactSliced(LaneState state, boolean sweepCancelled) {
        ArrayList<Entry> entries = state.sliced;
        int size = entries.size();
        int oldCursor = size == 0 ? 0 : Math.floorMod(state.slicedCursor, size);
        int successor = -1;
        int kept = 0;
        for (int i = 0; i < size; i++) {
            Entry e = entries.get(i);
            if (e == null) {
                continue;
            }
            if (sweepCancelled && e.released) {
                if (e instanceof SpreadEntry spread) {
                    state.unlinkDebt(spread);
                    state.listedSpread--;
                    spread.slicedIndex = -1;
                    enrolledSpread--;
                }
                continue;
            }
            if (e instanceof SpreadEntry spread) {
                if (spread.dormant) {
                    state.parkSpread(spread);
                    continue;
                }
                spread.slicedIndex = kept;
            }
            if (successor < 0 && i >= oldCursor) {
                successor = kept;
            }
            if (kept != i) {
                entries.set(kept, e);
            }
            kept++;
        }
        if (kept != size) {
            for (int i = size - 1; i >= kept; i--) {
                entries.remove(i);
            }
            state.slicedCursor = successor < 0 ? 0 : successor;
            state.slicedWakeNanos = Long.MIN_VALUE;
        }
        state.slicedTombstones = 0;
        state.slicedNeedsCompaction = false;
    }

    private void drainSliced(LaneState state, long budget, boolean markCallback) {
        long remaining = budget;
        int size = state.sliced.size();
        int skipped = 0;
        int steps = 0;
        long pumpCount = state.pumpCount;
        int at = size > 0 ? Math.floorMod(state.slicedCursor, size) : 0;
        int advance = 0;
        try {
            while (remaining > 0L && size > 0 && steps < MAX_SLICE_STEPS_PER_PUMP) {
                Entry e = state.sliced.get(at);
                advance++;
                if (++at == size) {
                    at = 0;
                }
                SpreadEntry spread = e instanceof SpreadEntry part ? part : null;
                if (e == null || e.cancelled || e.paused || e.failedOnPump == pumpCount
                        || e.yieldedOnPump == pumpCount
                        || (spread != null && !spread.readyForBudget())) {
                    if (++skipped >= size) {
                        break;
                    }
                    continue;
                }
                long before = 0L;
                if (spread != null) {
                    before = clock.getAsLong();
                    if (spread.cancelled || spread.paused) {
                        if (++skipped >= size) {
                            break;
                        }
                        continue;
                    }
                }
                skipped = 0;
                steps++;
                remaining -= spread == null
                        ? step(e, state, remaining, markCallback)
                        : stepSpread(spread, state, remaining, before, markCallback);
            }
            state.slicedCursor += advance;
            freeChunkForStarvedSliced(state, markCallback);
        } finally {
            if (state.slicedNeedsCompaction) {
                state.slicedCursor = at;
            }
        }
    }
    private long step(Entry e, LaneState state, long remainingNanos, boolean markCallback) {
        long pumpCount = state.pumpCount;
        long before = clock.getAsLong();
        return step(e, state, remainingNanos, markCallback, pumpCount, before);
    }

    private long step(Entry e, LaneState state, long remainingNanos, boolean markCallback,
                      long pumpCount, long before) {
        Step said;
        try {
            if (markCallback) {
                ownerCallback = true;
            }
            try {
                ValuesStep valuesStep = e.valuesStep;
                if (valuesStep == null) {
                    said = e.slicedJob.step(tickFor(e, before, pumpCount, remainingNanos));
                } else {
                    boolean first = firstAttempt(e);
                    long since = elapsedNanos(e, before, first);
                    long pumps = pumpsSince(e, pumpCount, first);
                    long runIndex = e.cleanRuns;
                    boolean catchUp = isCatchUp(e, first, since, pumps, runIndex);
                    tickValuesEngaged++;
                    said = valuesStep.step(since, pumps, catchUp, remainingNanos, runIndex);
                }
            } finally {
                if (markCallback) {
                    ownerCallback = false;
                }
            }
            if (said == null) {
                // Null result counts as YIELD.
                said = Step.YIELD;
            }
            e.recordCleanRun(before);
        } catch (Throwable crashed) {
            e.failedOnPump = pumpCount;
            said = recordFailure(e, crashed) ? Step.DONE : Step.YIELD;
        }
        long cost = finishSliceStep(e, state, remainingNanos, before, pumpCount);
        if (said == Step.DONE) {
            e.cancel();
        } else if (said == Step.YIELD) {
            e.yieldedOnPump = pumpCount;
        }
        return cost;
    }
    private long stepSpread(SpreadEntry e, LaneState state, long remainingNanos, long before,
                            boolean markCallback) {
        long pumpCount = state.pumpCount;
        e.inCallback = true;
        try {
            try {
                boolean committed;
                if (markCallback) {
                    ownerCallback = true;
                }
                try {
                    committed = e.work.tryRun(e.nextPart);
                } finally {
                    if (markCallback) {
                        ownerCallback = false;
                    }
                }
                if (committed) {
                    e.nextPart++;
                    if (FRAME_SPREAD) {
                        frameSpreadCompletedParts = plus(frameSpreadCompletedParts, 1L);
                    }
                    if (e.nextPart == e.parts) {
                        e.dormant = true;
                        state.slicedNeedsCompaction = true;
                        state.unlinkDebt(e);
                    }
                } else {
                    e.yieldedOnPump = pumpCount;
                }
                e.recordCleanRun(before);
            } catch (Throwable crashed) {
                e.failedOnPump = pumpCount;
                recordFailure(e, crashed);
            }
            return finishSliceStep(e, state, remainingNanos, before, pumpCount);
        } finally {
            e.inCallback = false;
        }
    }

    private long finishSliceStep(Entry e, LaneState state, long remainingNanos, long before,
                                 long pumpCount) {
        long cost = clock.getAsLong() - before;
        e.recordCost(cost, remainingNanos);
        e.lastRunNanos = before;
        e.lastRunPump = pumpCount;
        state.ran++;
        state.callbackNanos += cost;
        return cost;
    }

    private void freeChunkForStarvedSliced(LaneState state, boolean markCallback) {
        Entry legacy = mostOverdueSliced(state);
        SpreadEntry debt = FRAME_SPREAD ? firstEligibleDebt(state) : null;
        if (debt != null && (legacy == null || state.spreadFreeTurn)
                && grantFreeSpread(state, debt, markCallback)) {
            state.unlinkDebt(debt);
            if (debt.active() && !debt.cancelled) {
                state.appendDebt(debt);
            }
            if (legacy != null) {
                state.spreadFreeTurn = false;
            }
            return;
        }
        if (legacy instanceof SpreadEntry spread) {
            grantFreeSpread(state, spread, markCallback);
        } else if (legacy != null && grantFreeLegacy(state, legacy, markCallback) && debt != null) {
            state.spreadFreeTurn = true;
        }
    }

    private boolean grantFreeLegacy(LaneState state, Entry e, boolean markCallback) {
        if (markCallback && ownerStrict) {
            if (e.cancelled || e.paused) {
                return false;
            }
            long pumpCount = state.pumpCount;
            long before = clock.getAsLong();
            if (e.cancelled || e.paused) {
                return false;
            }
            state.freeChunks++;
            step(e, state, 0L, markCallback, pumpCount, before);
        } else {
            state.freeChunks++;
            step(e, state, 0L, markCallback);
        }
        return true;
    }

    private static SpreadEntry firstEligibleDebt(LaneState state) {
        for (SpreadEntry e = state.debtHead; e != null; e = e.debtNext) {
            if (readyForFreeSpread(e, state.pumpCount)) {
                return e;
            }
        }
        return null;
    }

    private static boolean readyForFreeSpread(SpreadEntry e, long pumpCount) {
        return e.ready && e.active() && !e.cancelled && !e.paused
                && e.lastRunPump != pumpCount && e.failedOnPump != pumpCount
                && e.yieldedOnPump != pumpCount;
    }

    private boolean grantFreeSpread(LaneState state, SpreadEntry e, boolean markCallback) {
        long before = clock.getAsLong();
        if (!readyForFreeSpread(e, state.pumpCount)) {
            return false;
        }
        state.freeChunks++;
        stepSpread(e, state, 0L, before, markCallback);
        return true;
    }

    private Entry mostOverdueSliced(LaneState state) {
        int size = state.sliced.size();
        if (size == 0 || (FRAME_SPREAD && size - state.slicedTombstones == state.listedSpread)) {
            return null;
        }
        long now = clock.getAsLong();
        if (now < state.slicedWakeNanos) {
            return null;
        }
        Entry worst = null;
        long mostOverdue = 0L;
        long wake = Long.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            Entry e = state.sliced.get(i);
            if (e == null || e.cancelled) {
                continue;
            }
            if (e.paused || (e instanceof SpreadEntry spread
                    && (FRAME_SPREAD || !spread.ready || !spread.active()))) {
                continue;
            }
            long since = e.lastRunPump == 0L ? e.registeredAtNanos : e.lastRunNanos;
            long deadline = e.spec.starvationNanos();
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
            if (worst == null || overdue > mostOverdue) {
                mostOverdue = overdue;
                worst = e;
            }
        }
        if (worst == null) {
            state.slicedWakeNanos = wake;
        }
        return worst;
    }
    private static final int MAX_SLICE_STEPS_PER_PUMP = 4096;
    // Negative sentinel for not overdue.
    private static final long NOT_OVERDUE = -1L;
    // Returns overdue time in nanoseconds.
    private static long overdueNanos(long sinceNanos, long deadlineNanos, long now) {
        long waited = now - sinceNanos;
        if (waited < deadlineNanos) {
            return NOT_OVERDUE;
        }
        return waited - deadlineNanos;
    }
    private static final int ESCALATION_DRAIN_PUMPS = 13;
    private static boolean escalates(Entry e) {
        if (!e.spec.hasDeadline() || e.mustRun) {
            return false;
        }
        return e.neverDrop || e.consecutiveEscalationOverruns < MAX_CONSECUTIVE_FAILURES;
    }
    // Overdue against the escalation deadline.
    private static long escalationOverdue(Entry e, long now) {
        if (!escalates(e)) {
            return NOT_OVERDUE;
        }
        long since = e.cleanRuns == 0L ? e.registeredAtNanos : e.lastCleanRunNanos;
        return overdueNanos(since, e.spec.deadlineNanos(), now);
    }
    // Runs expired deferrable jobs outside the budget.
    private long escalateExpired(LaneState state, ArrayList<Entry> deferrable,
                                 long budgetNanos, long spent, boolean markCallback) {
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
                if (worst == null || overdue > mostOverdue) {
                    mostOverdue = overdue;
                    worst = e;
                }
            }
            if (worst == null) {
                // Stops when no candidate remains.
                break;
            }
            // Uses the returned time as the next now.
            long wasAt = now;
            now = run(worst, state, now, budgetNanos - spent, markCallback);
            long cost = now - wasAt;
            spent += cost;
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
    private long run(Entry e, LaneState state, long now, long remainingNanos,
                     boolean markCallback) {
        long pumpCount = state.pumpCount;
        ValuesJob valuesJob = e.valuesJob;
        Tick tick;
        long since;
        long pumps;
        boolean catchUp;
        long runIndex;
        if (valuesJob == null) {
            tick = tickFor(e, now, pumpCount, remainingNanos);
            since = 0L;
            pumps = 0L;
            catchUp = false;
            runIndex = 0L;
        } else {
            tick = null;
            boolean first = firstAttempt(e);
            since = elapsedNanos(e, now, first);
            pumps = pumpsSince(e, pumpCount, first);
            runIndex = e.cleanRuns;
            catchUp = isCatchUp(e, first, since, pumps, runIndex);
        }
        long before = clock.getAsLong();
        long ended;
        try {
            if (markCallback) {
                ownerCallback = true;
            }
            try {
                if (valuesJob == null) {
                    e.job.run(tick);
                } else {
                    tickValuesEngaged++;
                    valuesJob.run(since, pumps, catchUp, remainingNanos, runIndex);
                }
            } finally {
                if (markCallback) {
                    ownerCallback = false;
                }
            }
            e.recordCleanRun(now);
        } catch (Throwable crashed) {
            recordFailure(e, crashed);
        } finally {
            ended = clock.getAsLong();
            e.recordCost(ended - before, remainingNanos);
            state.callbackNanos += ended - before;
            e.due = false;
            e.dueSinceNanos = 0L;
            e.lastRunNanos = now;
            e.lastRunPump = pumpCount;
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
    private static Tick tickFor(Entry e, long now, long pumpCount, long remainingNanos) {
        boolean first = firstAttempt(e);
        long since = elapsedNanos(e, now, first);
        long pumps = pumpsSince(e, pumpCount, first);
        long runIndex = e.cleanRuns;
        boolean catchUp = isCatchUp(e, first, since, pumps, runIndex);
        return new Tick(since, pumps, catchUp, remainingNanos, runIndex);
    }
    private static boolean firstAttempt(Entry e) {
        return e.lastRunPump == 0L;
    }
    private static long elapsedNanos(Entry e, long now, boolean first) {
        return first ? 0L : now - e.lastRunNanos - e.pausedNanosSinceRun;
    }
    private static long pumpsSince(Entry e, long pumpCount, boolean first) {
        return first ? 0L : pumpCount - e.lastRunPump;
    }
    private static boolean isCatchUp(Entry e, boolean first, long since, long pumps, long runIndex) {
        boolean behind = e.timeBased ? (since > e.lateAfterNanos) : (pumps > e.periodPumps);
        return !first && behind && (runIndex != 0L);
    }
    // Lifetime totals; nothing resets them.
    public record LaneStats(long pumps, long ran, long deferred, int periodic, int sliced,
                           long freeChunks, long callbackNanos) {
    }
    public LaneStats stats(Lane lane) {
        reportIfOffThread(Call.STATS);
        LaneState s = lanes[lane.ordinal()];
        return new LaneStats(s.pumpCount, s.ran, s.deferred, s.periodic.size(), s.slicedCount(),
                s.freeChunks, s.callbackNanos);
    }
    // Counts direct values callbacks across all lanes.
    public long tickValuesEngaged() {
        reportIfOffThread(Call.TICK_VALUES_ENGAGED);
        return tickValuesEngaged;
    }
    /** Counts batches whose pacing has been evaluated. */
    public long frameSpreadEngaged() {
        return checkSpreadOwner("Scheduler.frameSpreadEngaged") ? frameSpreadEngaged : 0L;
    }

    /** Counts batch deadlines missed on all lanes. */
    public long frameSpreadLate() {
        return checkSpreadOwner("Scheduler.frameSpreadLate") ? frameSpreadLate : 0L;
    }

    /** Counts parts committed with pacing enabled. */
    public long frameSpreadCompletedParts() {
        return checkSpreadOwner("Scheduler.frameSpreadCompletedParts") ? frameSpreadCompletedParts : 0L;
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
        CoreLog.verbose(String.format(Locale.ROOT,
                "%s cost table: pump %d, %d periodic, %d sliced; lane totals ran %d,"
                        + " deferred %d, free chunks %d",
                lane, state.pumpCount, state.periodic.size(), state.slicedCount(),
                state.ran, state.deferred, state.freeChunks));
        int shown = reportCostRows(lane, state.periodic, false, 0);
        shown = reportCostRows(lane, state.sliced, true, shown);
        for (SpreadEntry e = state.parkedHead; e != null && shown < MAX_COST_ROWS_PER_LANE; ) {
            SpreadEntry next = e.parkedNext;
            reportCostRow(lane, e, "sliced");
            shown++;
            e = next;
        }
        int entries = state.periodic.size() + state.slicedCount();
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
            if (e != null) {
                reportCostRow(lane, e, kind);
                shown++;
            }
        }
        return shown;
    }

    private static void reportCostRow(Lane lane, Entry e, String kind) {
        CoreLog.verbose(String.format(Locale.ROOT,
                "%s #%d %s (%s) runs %d avg %dus peak %dus%s def %d over %d%s",
                lane, e.index, e.costName, kind, e.runs,
                e.costEmaNanos / 1_000L, e.peakCostNanos / 1_000L,
                e.costDeadline,
                e.deferrals, e.overruns, conditionOf(e)));
    }
    private static String nameOf(JobSpec spec) {
        return spec.owner().isEmpty() ? spec.label() : spec.owner() + "/" + spec.label();
    }
    private static String deadlineOf(JobSpec spec, boolean sliced, boolean mustRun) {
        if (sliced || mustRun) {
            return "";
        }
        return spec.hasDeadline()
                ? " dl " + (spec.deadlineNanos() / 1_000_000L) + "ms"
                : " dl NONE";
    }
    private static String conditionOf(Entry e) {
        if (e.cancelled) {
            return " CANCELLED";
        }
        if (e.paused) {
            return " PAUSED";
        }
        return e instanceof SpreadEntry spread && !spread.active() ? " DORMANT" : "";
    }
    private static final class LaneState {
        final ArrayList<Entry> periodic = new ArrayList<>(16);
        final ArrayList<Entry> sliced = new ArrayList<>(16);
        final ArrayList<Entry> deferrable = new ArrayList<>(16);
        final Map<Long, Long> nextPhase = new HashMap<>();
        final Map<Long, Integer> nextTimePhase = new HashMap<>();
        long pumpCount;
        long ran;
        long callbackNanos;
        long deferred;
        long freeChunks;
        int slicedCursor;
        int listedSpread;
        int slicedTombstones;
        boolean slicedNeedsCompaction;
        int parkedSpread;
        SpreadEntry parkedHead;
        SpreadEntry parkedTail;
        SpreadEntry deadlineHead;
        SpreadEntry deadlineTail;
        SpreadEntry debtHead;
        SpreadEntry debtTail;
        boolean spreadFreeTurn;
        int deadlined;
        long wakePump = Long.MIN_VALUE;
        long wakeNanos = Long.MIN_VALUE;
        volatile long slicedWakeNanos = Long.MIN_VALUE;
        final AtomicInteger cancellations = new AtomicInteger();
        volatile boolean rescan;
        long nextCostReportNanos;
        final LongSupplier clock;
        final Scheduler owner;
        LaneState(Scheduler owner, LongSupplier clock) {
            this.owner = owner;
            this.clock = clock;
        }
        int slicedCount() {
            return sliced.size() - slicedTombstones + parkedSpread;
        }

        void reserveSlicedCapacity(int extra) {
            long needed = (long) slicedCount() + listedSpread + parkedSpread + extra;
            if (needed > Integer.MAX_VALUE) {
                throw new OutOfMemoryError("Too many sliced handles");
            }
            sliced.ensureCapacity((int) needed);
        }

        void appendSpread(SpreadEntry e) {
            e.slicedIndex = sliced.size();
            sliced.add(e);
            listedSpread++;
        }

        void parkSpread(SpreadEntry e) {
            e.slicedIndex = -1;
            listedSpread--;
            e.parked = true;
            e.parkedPrev = parkedTail;
            if (parkedTail == null) {
                parkedHead = e;
            } else {
                parkedTail.parkedNext = e;
            }
            parkedTail = e;
            parkedSpread++;
        }

        void unlinkParked(SpreadEntry e) {
            SpreadEntry previous = e.parkedPrev;
            SpreadEntry next = e.parkedNext;
            if (previous == null) {
                parkedHead = next;
            } else {
                previous.parkedNext = next;
            }
            if (next == null) {
                parkedTail = previous;
            } else {
                next.parkedPrev = previous;
            }
            e.parkedPrev = null;
            e.parkedNext = null;
            e.parked = false;
            parkedSpread--;
        }

        void relinkSpread(SpreadEntry e) {
            if (e.parked) {
                unlinkParked(e);
            } else {
                sliced.set(e.slicedIndex, null);
                slicedTombstones++;
                listedSpread--;
            }
            appendSpread(e);
        }

        void appendDebt(SpreadEntry e) {
            e.debtPrev = debtTail;
            if (debtTail == null) {
                debtHead = e;
            } else {
                debtTail.debtNext = e;
            }
            debtTail = e;
        }

        void unlinkDebt(SpreadEntry e) {
            SpreadEntry prev = e.debtPrev;
            SpreadEntry next = e.debtNext;
            if (prev == null) {
                if (debtHead != e) {
                    return;
                }
                debtHead = next;
            } else {
                prev.debtNext = next;
            }
            if (next == null) {
                debtTail = prev;
            } else {
                next.debtPrev = prev;
            }
            e.debtPrev = null;
            e.debtNext = null;
        }

        long claimPhase(long periodPumps, long firstPump) {
            long phase = nextPhase.getOrDefault(periodPumps, firstPump);
            nextPhase.put(periodPumps, phase + 1);
            return phase;
        }
        int claimTimePhase(long periodNanos) {
            int phase = nextTimePhase.getOrDefault(periodNanos, 0);
            nextTimePhase.put(periodNanos, phase + 1);
            return phase;
        }
    }
    private static final class SpreadEntry extends Entry implements FrameSpreadHandle {
        final int parts;
        final int maxPumps;
        final int phase;
        FramePart work;
        int nextPart;
        int eligiblePumps;
        int target;
        boolean ready;
        boolean late;
        boolean dormant;
        int slicedIndex;
        boolean parked;
        SpreadEntry parkedPrev;
        SpreadEntry parkedNext;
        boolean inCallback;
        SpreadEntry deadlineNext;
        SpreadEntry debtPrev;
        SpreadEntry debtNext;

        SpreadEntry(JobSpec spec, int index, LaneState lane, int parts, int maxPumps,
                    FramePart work, long now) {
            super(spec, index, lane, true);
            this.parts = parts;
            this.maxPumps = maxPumps;
            this.phase = FRAME_SPREAD ? Math.floorMod(index, maxPumps) : 0;
            this.work = work;
            registeredAtNanos = now;
        }

        boolean active() {
            return !dormant;
        }

        boolean readyForBudget() {
            return ready && active() && (!FRAME_SPREAD || nextPart < target);
        }

        @Override
        public boolean rearm() {
            Scheduler owner = lane.owner;
            owner.checkSpreadOwner("FrameSpreadHandle.rearm");
            if (active() || inCallback || cancelled) {
                return false;
            }
            owner.ownerMutationDepth++;
            inCallback = true;
            try {
                if (!FRAME_SPREAD) {
                    long now = lane.clock.getAsLong();
                    if (cancelled) {
                        return false;
                    }
                    registeredAtNanos = now;
                    lastRunPump = 0L;
                    lane.slicedWakeNanos = Long.MIN_VALUE;
                }
                lane.relinkSpread(this);
                nextPart = 0;
                eligiblePumps = 0;
                target = 0;
                ready = false;
                late = false;
                dormant = false;
                return true;
            } finally {
                inCallback = false;
                owner.leaveOwnerCall(false);
            }
        }

        @Override
        public boolean isLate() {
            lane.owner.checkSpreadOwner("FrameSpreadHandle.isLate");
            return late;
        }
    }

    private static class Entry implements Handle {
        final JobSpec spec;
        final int index;
        final LaneState lane;
        Job job;
        SlicedJob slicedJob;
        ValuesJob valuesJob;
        ValuesStep valuesStep;
        final boolean sliced;
        volatile boolean expedited;
        boolean released;
        final boolean timeBased;
        final boolean mustRun;
        final boolean neverDrop;
        final int priorityWeight;
        // Pump on which this job last yielded.
        long yieldedOnPump = -1L;
        volatile long periodPumps;
        volatile long periodNanos;
        // Derived from periodNanos.
        volatile long lateAfterNanos;
        long registeredAtNanos;
        long nextDuePump;
        long nextDueNanos;
        boolean due;
        long dueSinceNanos;
        volatile long pausedAtNanos = Long.MIN_VALUE;
        long pausedNanosSinceRun;
        long lastRunNanos;
        long lastRunPump;
        long lastCleanRunNanos;
        long costEmaNanos;
        long deferrals;
        // Counts every run entered.
        long runs;
        // Counts runs that returned.
        long cleanRuns;
        long peakCostNanos;
        long overruns;
        int consecutiveFailures;
        int consecutiveEscalationOverruns;
        long failedOnPump;
        volatile boolean cancelled;
        volatile boolean paused;
        final long starvationDeadlineNanos;
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
        void recordCost(long nanos, long given) {
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
            lane.cancellations.incrementAndGet();
        }
        @Override
        public void setPaused(boolean p) {
            if (p) {
                pausedAtNanos = lane.clock.getAsLong();
            }
            paused = p;
            if (!p) {
                lane.rescan = true;
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
            if (cancelled || paused) {
                return;
            }
            expedited = true;
            lane.rescan = true;
        }
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
            boolean ownerCall = lane.owner.enterMutation(Call.RETIME);
            try {
                lane.owner.reportIfOffThreadRetime("setPeriodMillis");
                if (millis < 1) {
                    throw new IllegalArgumentException("millis must be >= 1, was " + millis);
                }
                if (!spec.timeBased()) {
                    throw new IllegalStateException("Job '" + spec.label() + "' counts pumps,"
                            + " not time. Use setPeriodPumps or"
                            + " JobSpec.everyMillis.");
                }
                long wanted = JobSpec.nanos(millis, "millis");
                if (spec.hasDeadline() && wanted >= spec.deadlineNanos()) {
                    wanted = spec.deadlineNanos() - 1L;
                }
                if (wanted == periodNanos) {
                    return;
                }
                periodNanos = wanted;
                lateAfterNanos = plus(wanted, wanted / 2L);
                rearmNanos(wanted);
            } finally {
                lane.owner.leaveCall(ownerCall, false);
            }
        }
        @Override
        public void setPeriodPumps(long pumps) {
            boolean ownerCall = lane.owner.enterMutation(Call.RETIME);
            try {
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
            } finally {
                lane.owner.leaveCall(ownerCall, false);
            }
        }
        private void rearmNanos(long period) {
            long now = lane.clock.getAsLong();
            long armed = lastRunPump == 0L ? nextDueNanos : plus(lastRunNanos, period);
            nextDueNanos = Math.min(armed, plus(now, period));
            standDownIfDeferred(nextDueNanos > now);
            lane.rescan = true;
        }
        private void rearmPumps(long period) {
            long seen = lane.pumpCount;
            long armed = lastRunPump == 0L ? nextDuePump : plus(lastRunPump, period);
            nextDuePump = Math.min(armed, plus(seen, period));
            standDownIfDeferred(nextDuePump > seen);
            lane.rescan = true;
        }
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
