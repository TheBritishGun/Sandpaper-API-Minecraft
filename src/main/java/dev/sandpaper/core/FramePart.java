package dev.sandpaper.core;

@FunctionalInterface
public interface FramePart {
    /** Runs one part; true commits it, false retries later. */
    boolean tryRun(int partIndex);
}
