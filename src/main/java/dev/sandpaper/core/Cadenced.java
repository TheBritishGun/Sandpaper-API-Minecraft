package dev.sandpaper.core;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
public final class Cadenced<T> {
    private static final AtomicInteger UNNAMED = new AtomicInteger();
    // Written by the pump thread, read from the drawing thread.
    private volatile T value;
    private Handle handle;
    private Cadenced(T initial) {
        this.value = initial;
    }
    public static <T> Cadenced<T> register(Scheduler scheduler, JobSpec spec,
                                           T initial, Supplier<T> compute) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(compute, "compute");
        Cadenced<T> cadenced = new Cadenced<>(initial);
        cadenced.handle = scheduler.register(spec, tick -> cadenced.value = compute.get());
        return cadenced;
    }
    public static <T> Cadenced<T> everyOtherFrame(Scheduler scheduler, T initial,
                                                  Supplier<T> compute) {
        return everyOtherFrame(scheduler, initial, compute,
                "cadenced-hud-" + UNNAMED.incrementAndGet());
    }
    // The label appears in a dropped-job report, to say whose job it was.
    public static <T> Cadenced<T> everyOtherFrame(Scheduler scheduler, T initial,
                                                  Supplier<T> compute, String label) {
        return register(scheduler, JobSpec.everyPumps(Lane.RENDER, 2)
                .withLabel(label), initial, compute);
    }
    public T get() {
        return value;
    }
    // True only when cancelled, not when merely paused.
    public boolean isStopped() {
        return handle.isCancelled();
    }
    // Call from the registration thread to pause, retime or cancel.
    public Handle handle() {
        return handle;
    }
}
