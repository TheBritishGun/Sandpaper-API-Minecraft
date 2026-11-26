package dev.sandpaper.core;

@FunctionalInterface
public interface ValuesStep {
    Step step(long sinceLastRunNanos, long pumpsSinceLastRun, boolean catchUp, long remainingNanos, long runIndex);
}
