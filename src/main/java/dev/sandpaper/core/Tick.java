package dev.sandpaper.core;
public record Tick(long sinceLastRunNanos, long pumpsSinceLastRun, boolean catchUp,
                   long remainingNanos, long runIndex) {
    public Tick {
        if (runIndex == 0L) {
            catchUp = false;
        }
    }
    // True before this job's first completed run; a thrown run does not count.
    public boolean first() {
        return runIndex == 0L;
    }
    public double sinceLastRunSeconds() {
        return sinceLastRunNanos / 1_000_000_000.0;
    }
    // Snapshot at entry; does not update within one run.
    public boolean hasTimeLeft() {
        return remainingNanos > 0L;
    }
    // Only a mustRun job can see this true.
    public boolean overBudget() {
        return remainingNanos < 0L;
    }
}
