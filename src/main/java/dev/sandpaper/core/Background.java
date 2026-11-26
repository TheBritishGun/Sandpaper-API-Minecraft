package dev.sandpaper.core;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
public final class Background {
    public static final int HEADROOM = 1;
    public static final int PRIORITY = Thread.NORM_PRIORITY;
    private Background() {
    }
    public static int threads() {
        return threadsFor(Runtime.getRuntime().availableProcessors());
    }
    static int threadsFor(int processors) {
        return Math.max(1, processors - HEADROOM);
    }
    public static Thread thread(Runnable work, String name) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(name, "name");
        Thread t = new Thread(work, name);
        t.setDaemon(true);
        setBackgroundPriority(t);
        return t;
    }
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
    public static <T> T call(Callable<T> work) throws Exception {
        Objects.requireNonNull(work, "work");
        Thread self = Thread.currentThread();
        int was = self.getPriority();
        setBackgroundPriority(self);
        try {
            return work.call();
        } finally {
            set(self, was);
        }
    }
    private static void setBackgroundPriority(Thread t) {
        set(t, PRIORITY);
    }
    private static void set(Thread t, int priority) {
        try {
            t.setPriority(priority);
        } catch (RuntimeException refused) {
        }
    }
}
