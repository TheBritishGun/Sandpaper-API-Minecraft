package dev.sandpaper.core;
import java.util.Objects;
public final class JobSpec {
    public static final long DEFAULT_STARVATION_NANOS = 2_000_000_000L;
    public static final long MAX_MILLIS = Long.MAX_VALUE / 1_000_000L;
    public static final long NO_DEADLINE = 0L;
    private final Lane lane;
    private final long periodPumps;
    private final long periodNanos;
    private final Priority priority;
    private final boolean mustRun;
    private final long starvationNanos;
    private final long deadlineNanos;
    private final String label;
    private final boolean neverDrop;
    private final String owner;
    private JobSpec(Lane lane, long periodPumps, long periodNanos, Priority priority,
                    boolean mustRun, long starvationNanos, long deadlineNanos, String label,
                    boolean neverDrop, String owner) {
        this.lane = Objects.requireNonNull(lane, "lane");
        this.periodPumps = periodPumps;
        this.periodNanos = periodNanos;
        this.priority = Objects.requireNonNull(priority, "priority");
        this.mustRun = mustRun;
        this.starvationNanos = starvationNanos;
        this.deadlineNanos = deadlineNanos;
        this.label = Objects.requireNonNull(label, "label");
        this.neverDrop = neverDrop;
        this.owner = Objects.requireNonNull(owner, "owner");
    }
    public static JobSpec everyPumps(Lane lane, int pumps) {
        if (pumps < 1) throw new IllegalArgumentException("pumps must be >= 1, was " + pumps);
        return new JobSpec(lane, pumps, 0L, Priority.NORMAL, false, DEFAULT_STARVATION_NANOS,
                NO_DEADLINE, "job", false, "");
    }
    public static JobSpec everyMillis(Lane lane, long millis) {
        if (millis < 1) throw new IllegalArgumentException("millis must be >= 1, was " + millis);
        return new JobSpec(lane, 0L, nanos(millis, "millis"), Priority.NORMAL, false,
                DEFAULT_STARVATION_NANOS, NO_DEADLINE, "job", false, "");
    }
    public static JobSpec everyPump(Lane lane) {
        return everyPumps(lane, 1);
    }
    static long nanos(long millis, String what) {
        if (millis > MAX_MILLIS) {
            throw new IllegalArgumentException(what + " must be <= " + MAX_MILLIS
                    + ", was " + millis);
        }
        return millis * 1_000_000L;
    }
    public JobSpec withPriority(Priority p) {
        return new JobSpec(lane, periodPumps, periodNanos, p, mustRun, starvationNanos,
                deadlineNanos, label, neverDrop, owner);
    }
    public JobSpec mustRun() {
        return new JobSpec(lane, periodPumps, periodNanos, priority, true, starvationNanos,
                deadlineNanos, label, neverDrop, owner);
    }
    public JobSpec withStarvationMillis(long millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("starvation millis must be >= 0, was " + millis);
        }
        return new JobSpec(lane, periodPumps, periodNanos, priority, mustRun,
                nanos(millis, "starvation millis"), deadlineNanos, label, neverDrop, owner);
    }
    public JobSpec withLabel(String l) {
        return new JobSpec(lane, periodPumps, periodNanos, priority, mustRun, starvationNanos,
                deadlineNanos, l, neverDrop, owner);
    }
    public JobSpec neverDrop() {
        return new JobSpec(lane, periodPumps, periodNanos, priority, mustRun, starvationNanos,
                deadlineNanos, label, true, owner);
    }
    public JobSpec withOwner(String o) {
        if (o == null || o.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank; omit withOwner "
                    + "for an unowned job");
        }
        return new JobSpec(lane, periodPumps, periodNanos, priority, mustRun, starvationNanos,
                deadlineNanos, label, neverDrop, o);
    }
    public JobSpec withDeadlineMillis(long millis) {
        long floorMillis = timeBased() ? periodNanos / 1_000_000L : 0L;
        if (millis <= floorMillis) {
            String cadence = timeBased()
                    ? "cadence of " + floorMillis + " ms"
                    : "cadence (none in ms for a pump job)";
            throw new IllegalArgumentException("deadline millis must be ABOVE this job's own "
                    + cadence + ", was " + millis + ".");
        }
        return new JobSpec(lane, periodPumps, periodNanos, priority, mustRun, starvationNanos,
                nanos(millis, "deadline millis"), label, neverDrop, owner);
    }
    public Lane lane() { return lane; }
    public long periodPumps() { return periodPumps; }
    public long periodNanos() { return periodNanos; }
    public boolean timeBased() { return periodNanos > 0; }
    public Priority priority() { return priority; }
    public boolean isMustRun() { return mustRun; }
    public long starvationNanos() { return starvationNanos; }
    public long deadlineNanos() { return deadlineNanos; }
    public boolean hasDeadline() { return deadlineNanos != NO_DEADLINE; }
    public String label() { return label; }
    public boolean isNeverDropped() { return neverDrop; }
    public String owner() { return owner; }
}
