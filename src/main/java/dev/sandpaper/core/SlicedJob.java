package dev.sandpaper.core;
@FunctionalInterface
public interface SlicedJob {
    Step step(Tick tick);
}
