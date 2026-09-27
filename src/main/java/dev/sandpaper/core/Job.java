package dev.sandpaper.core;
// Completes within a single run.
@FunctionalInterface
public interface Job {
    void run(Tick tick);
}
