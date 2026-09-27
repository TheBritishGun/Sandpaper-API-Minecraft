package dev.sandpaper.core;
// Work in pieces stepped from leftover lane budget; do the smallest useful unit per step.
@FunctionalInterface
public interface SlicedJob {
    // Returns what to do with the next pump; see Step.
    Step step(Tick tick);
}
