package dev.sandpaper.core;
public interface Handle {
    String label();
    boolean isCancelled();
    void cancel();
    void setPaused(boolean paused);
    boolean isPaused();
    void expedite();
    long averageCostNanos();
    long deferrals();
    long runs();
    long peakCostNanos();
    long overruns();
    void setPeriodMillis(long millis);
    void setPeriodPumps(long pumps);
    long periodMillis();
    long periodPumps();
    int registrationIndex();
}
