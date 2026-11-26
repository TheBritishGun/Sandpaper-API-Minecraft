package dev.sandpaper.core;

@FunctionalInterface
public interface ValuesJob {
    void run(long sinceLastRunNanos, long pumpsSinceLastRun, boolean catchUp, long remainingNanos, long runIndex);
}
