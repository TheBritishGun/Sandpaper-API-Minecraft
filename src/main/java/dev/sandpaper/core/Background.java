package dev.sandpaper.core;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
public final class Background {
    // Processors left for the game; availableProcessors() counts hardware threads, not cores.
    public static final int HEADROOM = 2;
    public static final int MAX_THREADS = 6;
    public static final int PRIORITY =
            Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2);
    private Background() {
    }
    // Never below one.
    public static int threads() {
        return threadsFor(Runtime.getRuntime().availableProcessors());
    }
    static int threadsFor(int processors) {
        return Math.max(1, Math.min(processors - HEADROOM, MAX_THREADS));
    }
    // Returns unstarted; the caller starts it.
    public static Thread thread(Runnable work, String name) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(name, "name");
        Thread t = new Thread(work, name);
        t.setDaemon(true);
        lower(t);
        return t;
    }
    // Names threads by prefix plus a count local to this factory.
    public static ThreadFactory factory(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                return thread(r, prefix + n.incrementAndGet());
            }
        };
    }
    // Like call(), but for a Runnable; wraps any checked exception as unchecked.
    public static void run(Runnable work) {
        Objects.requireNonNull(work, "work");
        try {
            call(() -> {
                work.run();
                return null;
            });
        } catch (RuntimeException | Error unchecked) {
            throw unchecked;
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    // Runs work on the current thread at a lower priority; restores the priority in a finally block.
    public static <T> T call(Callable<T> work) throws Exception {
        Objects.requireNonNull(work, "work");
        Thread self = Thread.currentThread();
        int was = self.getPriority();
        lower(self);
        try {
            return work.call();
        } finally {
            set(self, was);
        }
    }
    private static void lower(Thread t) {
        set(t, PRIORITY);
    }
    // Android's setPriority clamps silently rather than throwing.
    private static void set(Thread t, int priority) {
        try {
            t.setPriority(priority);
        } catch (RuntimeException refused) {
        }
    }
}
