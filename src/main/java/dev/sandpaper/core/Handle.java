package dev.sandpaper.core;
public interface Handle {
    String label();
    boolean isCancelled();
    void cancel();
    void setPaused(boolean paused);
    boolean isPaused();
    // Any thread; throws IllegalStateException for a handle from registerSliced.
    void expedite();
    // Damped average; read from pumping thread only.
    long averageCostNanos();
    // Runs skipped for lack of budget; read from pumping thread only.
    long deferrals();
    // Completed runs; read from pumping thread only.
    long runs();
    // Read from pumping thread only.
    long peakCostNanos();
    // Runs over their allotted budget; a zero allotment does not count; read from pumping thread only.
    long overruns();
    // Retimes an everyMillis job; the next run is one new period after the last.
    // Pumping thread only; throws if this job counts pumps, or if millis is below 1.
    // Ignored on a sliced handle, which runs every pump.
    void setPeriodMillis(long millis);
    // Retimes an everyPumps job; a pump is not a duration.
    // Throws if this job counts time, if it has a deadline unequal to pumps, or if pumps is below 1.
    void setPeriodPumps(long pumps);
    // On a sliced handle, this does not say when the job runs.
    long periodMillis();
    // On a sliced handle, this does not say when the job runs.
    long periodPumps();
    // Dense from zero.
    int registrationIndex();
}
