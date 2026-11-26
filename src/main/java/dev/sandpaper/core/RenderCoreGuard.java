package dev.sandpaper.core;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Consumer;
public final class RenderCoreGuard {
    public interface Cores {
        int currentProcessor();
        int[] coreOfProcessor();
        boolean restrictCurrentThread(int[] allowedProcessors);
    }
    private static final int MAX_FAILURES = 3;
    private static final int LIFTED = -2;
    private static final int NONE = -1;
    private final Cores cores;
    private final int[] coreOfProcessor;
    private final Consumer<String> report;
    private volatile int excludedCore = -1;
    private volatile int[] allowed;
    private volatile int generation;
    private volatile boolean off;
    private int consecutiveFailures;
    private final ThreadLocal<int[]> held = ThreadLocal.withInitial(() -> new int[] {NONE});
    public RenderCoreGuard(Cores cores, Consumer<String> report) {
        this.cores = cores;
        this.report = report;
        this.coreOfProcessor = cores.coreOfProcessor();
        if (coreOfProcessor == null || coreOfProcessor.length < 2 || distinctCores(coreOfProcessor) < 2) {
            off = true;
        }
    }
    private static int distinctCores(int[] coreOf) {
        java.util.BitSet seen = new java.util.BitSet();
        for (int core : coreOf) {
            if (core >= 0) {
                seen.set(core);
            }
        }
        return seen.cardinality();
    }
    public boolean isOff() {
        return off;
    }
    public void sampleRenderThread() {
        if (off) {
            return;
        }
        int processor = cores.currentProcessor();
        if (processor < 0 || processor >= coreOfProcessor.length) {
            return;
        }
        int core = coreOfProcessor[processor];
        if (core == excludedCore || core < 0) {
            return;
        }
        allowed = allowedWithout(core);
        excludedCore = core;
        generation++;
    }
    int[] allowedWithout(int core) {
        int count = 0;
        for (int c : coreOfProcessor) {
            if (c >= 0 && c != core) {
                count++;
            }
        }
        int[] out = new int[count];
        int next = 0;
        for (int p = 0; p < coreOfProcessor.length; p++) {
            if (coreOfProcessor[p] >= 0 && coreOfProcessor[p] != core) {
                out[next++] = p;
            }
        }
        return out;
    }
    public void beforeWorkerTask() {
        if (off) {
            liftOwnLimit();
            return;
        }
        int current = generation;
        if (current == 0) {
            return;
        }
        int[] mine = held.get();
        if (mine[0] == current) {
            return;
        }
        int[] limit = allowed;
        boolean done;
        try {
            done = cores.restrictCurrentThread(limit);
        } catch (Throwable failed) {
            done = false;
        }
        if (done) {
            mine[0] = current;
            synchronized (this) {
                consecutiveFailures = 0;
            }
            if (off) {
                liftOwnLimit();
            }
            return;
        }
        String say = null;
        synchronized (this) {
            if (++consecutiveFailures >= MAX_FAILURES && !off) {
                off = true;
                say = "Sandpaper could not keep its workers off the render thread's core " + MAX_FAILURES
                        + " times in a row; the workers now run wherever the system puts them.";
            }
        }
        if (off) {
            liftOwnLimit();
        }
        if (say != null) {
            report.accept(say);
        }
    }
    private void liftOwnLimit() {
        int[] mine = held.get();
        if (mine[0] >= 1) {
            mine[0] = LIFTED;
            try {
                cores.restrictCurrentThread(null);
            } catch (Throwable ignored) {
            }
        }
    }
    public int excludedCore() {
        return excludedCore;
    }
    int generation() {
        return generation;
    }
    private static volatile RenderCoreGuard installed;
    public static void install(RenderCoreGuard guard) {
        installed = guard;
    }
    public static void sample() {
        RenderCoreGuard guard = installed;
        if (guard != null) {
            guard.sampleRenderThread();
        }
    }
    public static void beforeTask() {
        RenderCoreGuard guard = installed;
        if (guard != null) {
            try {
                guard.beforeWorkerTask();
            } catch (Throwable ignored) {
            }
        }
    }
    public static RenderCoreGuard forThisMachine(Consumer<String> report) {
        if ("off".equals(System.getProperty("sandpaper.keepWorkersOffRenderCore", ""))) {
            return null;
        }
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (!os.startsWith("windows")) {
            return null;
        }
        try {
            Cores machine = (Cores) Class.forName("dev.sandpaper.core.WindowsCores")
                    .getDeclaredConstructor().newInstance();
            RenderCoreGuard guard = new RenderCoreGuard(machine, report);
            return guard.isOff() ? null : guard;
        } catch (Throwable unavailable) {
            Throwable why = unavailable instanceof InvocationTargetException && unavailable.getCause() != null
                    ? unavailable.getCause() : unavailable;
            report.accept("Sandpaper cannot keep its workers off the render thread's core on this system (" + why
                    + "); the workers run wherever the system puts them.");
            return null;
        }
    }
}
