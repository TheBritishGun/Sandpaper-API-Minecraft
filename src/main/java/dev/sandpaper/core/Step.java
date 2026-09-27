package dev.sandpaper.core;
public enum Step {
    // Retired; will not step again unless reregistered.
    DONE,
    // More work ready now; the scheduler may re-step this pump.
    MORE,
    // More work, not ready this pump; waits on an outside resource or thread.
    YIELD
}
