package dev.sandpaper.core;
public record Tick(long sinceLastRunNanos, long pumpsSinceLastRun, boolean catchUp,
                   long remainingNanos, long runIndex) {
    public Tick {
        if (runIndex == 0L) {
            catchUp = false;
        }
    }
    public boolean first() {
        return runIndex == 0L;
    }
    public double sinceLastRunSeconds() {
        return sinceLastRunNanos / 1_000_000_000.0;
    }
    public boolean hasTimeLeft() {
        return remainingNanos > 0L;
    }
    public boolean overBudget() {
        return remainingNanos < 0L;
    }
}
