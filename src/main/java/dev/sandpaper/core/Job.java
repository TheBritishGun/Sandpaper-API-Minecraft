package dev.sandpaper.core;
@FunctionalInterface
public interface Job {
    void run(Tick tick);
}
