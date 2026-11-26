package dev.sandpaper.core;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
public final class Cadenced<T> {
    private static final AtomicInteger UNNAMED = new AtomicInteger();
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
        cadenced.handle = scheduler.registerValues(spec,
                (since, pumps, catchUp, remaining, runIndex) -> cadenced.value = compute.get());
        return cadenced;
    }
    public static <T> Cadenced<T> everyOtherFrame(Scheduler scheduler, T initial,
                                                  Supplier<T> compute) {
        return everyOtherFrame(scheduler, initial, compute,
                "cadenced-hud-" + UNNAMED.incrementAndGet());
    }
    public static <T> Cadenced<T> everyOtherFrame(Scheduler scheduler, T initial,
                                                  Supplier<T> compute, String label) {
        return register(scheduler, JobSpec.everyPumps(Lane.RENDER, 2)
                .withLabel(label), initial, compute);
    }
    public T get() {
        return value;
    }
    public boolean isStopped() {
        return handle.isCancelled();
    }
    public Handle handle() {
        return handle;
    }
}
