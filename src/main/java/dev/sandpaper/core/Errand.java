package dev.sandpaper.core;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
// Recurring work: computed off the game thread, applied on it; at most one send outstanding.
public final class Errand {
    private final WorkPool pool;
    // Empty string means unowned.
    private final String owner;
    private final AtomicBoolean out = new AtomicBoolean();
    // The out send's receipt, or null.
    private final AtomicReference<WorkPool.Receipt> pending = new AtomicReference<>();
    // Incremented once per accepted send.
    private final AtomicLong sequence = new AtomicLong();
    // Zero before the first apply.
    private long lastAppliedSequence;
    private Handle handle;
    private Errand(WorkPool pool, String owner) {
        this.pool = pool;
        this.owner = owner;
    }
    // Registers recurring off-thread compute with on-game apply; cadence is an upper bound on send frequency.
    // compute runs on a pool worker; its result goes to apply, which runs on the game thread.
    // Throws NullPointerException if any argument is null.
    public static <T> Errand register(Scheduler scheduler, WorkPool pool, JobSpec spec,
                                      Supplier<T> compute, Consumer<T> apply) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(compute, "compute");
        Objects.requireNonNull(apply, "apply");
        Errand errand = new Errand(pool, spec.owner());
        errand.handle = scheduler.register(spec, tick -> errand.send(compute, apply));
        return errand;
    }
    // Errand with no cadence; the caller drives sends from its own job; handle() returns null.
    // Throws NullPointerException if pool is null.
    public static Errand on(WorkPool pool) {
        Objects.requireNonNull(pool, "pool");
        return new Errand(pool, "");
    }
    // Sends if none is out; call from the scheduler pumping thread.
    // Returns true if the pool accepted the compute; false if already out or the pool refused.
    // Throws NullPointerException if compute or apply is null.
    public <T> boolean send(Supplier<T> compute, Consumer<T> apply) {
        Objects.requireNonNull(compute, "compute");
        Objects.requireNonNull(apply, "apply");
        releaseIfDroppedUnrun();
        if (!out.compareAndSet(false, true)) {
            return false;
        }
        WorkPool.Receipt receipt = new WorkPool.Receipt();
        long seq = sequence.incrementAndGet();
        Supplier<T> released = () -> {
            try {
                return compute.get();
            } finally {
                pending.compareAndSet(receipt, null);
                out.set(false);
            }
        };
        Consumer<T> sequenced = result -> {
            if (seq < lastAppliedSequence) {
                CoreLog.error("Sandpaper's Errand applied a result out of order: send #"
                        + seq + " reached apply after send #" + lastAppliedSequence
                        + ". Applied anyway.",
                        new IllegalStateException("out-of-order Errand apply: send #" + seq
                                + " after send #" + lastAppliedSequence));
            } else {
                lastAppliedSequence = seq;
            }
            apply.accept(result);
        };
        pending.set(receipt);
        boolean queued = owner.isEmpty()
                ? pool.submit(released, sequenced, receipt)
                : pool.submit(owner, released, sequenced, receipt);
        if (!queued) {
            pending.compareAndSet(receipt, null);
            out.set(false);
        }
        return queued;
    }
    private void releaseIfDroppedUnrun() {
        WorkPool.Receipt held = pending.get();
        if (held == null || !held.isDroppedUnrun()) {
            return;
        }
        if (!pending.compareAndSet(held, null)) {
            return;
        }
        out.set(false);
    }
    public boolean isOutstanding() {
        releaseIfDroppedUnrun();
        return out.get();
    }
    // Null for an errand built by on().
    public Handle handle() {
        return handle;
    }
}
