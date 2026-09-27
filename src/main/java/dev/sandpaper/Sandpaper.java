package dev.sandpaper;
import dev.sandpaper.core.Scheduler;
import dev.sandpaper.core.WorkPool;
public final class Sandpaper {
    public static final String MOD_ID = "sandpaper";
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(MOD_ID);
    static {
        // Sink must be installed before any job exists.
        dev.sandpaper.core.CoreLog.install(
                (message, thrown) -> LOGGER.error(message, thrown));
        dev.sandpaper.core.CoreLog.installVerbose(LOGGER::info);
    }
    private static volatile dev.sandpaper.config.SandpaperConfig config = new dev.sandpaper.config.SandpaperConfig();
    private static volatile java.nio.file.Path configPath;
    private static final Scheduler SCHEDULER = new Scheduler(System::nanoTime);
    private static volatile WorkPool workPool;
    private static volatile Thread installWatchdog;
    private static final long INSTALL_DEADLINE_MILLIS = 30_000L;
    private Sandpaper() {
    }
    public static Scheduler scheduler() {
        if (workPool == null) {
            armInstallWatchdog();
        }
        return SCHEDULER;
    }
    public static WorkPool workPool() {
        WorkPool p = workPool;
        if (p == null) {
            throw new IllegalStateException(
                    "Sandpaper's work pool is not installed yet. "
                            + "Submit from a scheduled job "
                            + "or a screen, "
                            + "not mod init.");
        }
        return p;
    }
    public static dev.sandpaper.config.SandpaperConfig config() {
        return config;
    }
    // Writes settings from the client thread; false means they are lost at next start.
    public static boolean save() {
        // Must run before the path check.
        dev.sandpaper.core.CoreLog.setVerbose(config.verboseLogging);
        java.nio.file.Path path = configPath;
        if (path == null) {
            LOGGER.error("No settings path is set yet; "
                    + "nothing was saved.");
            return false;
        }
        dev.sandpaper.config.SandpaperConfig live = config;
        if (live.unreadable != null) {
            LOGGER.error("{} could not be read "
                    + "at startup ({}); "
                    + "move or fix it and restart "
                    + "to save.", path, live.unreadable);
            return false;
        }
        try {
            reportSaveCorrections(path, live.normaliseAndListCorrections());
            live.save(path);
            return true;
        } catch (java.io.IOException | RuntimeException failed) {
            LOGGER.error("could not write its settings to {}.", path, failed);
            return false;
        }
    }
    private static void reportSaveCorrections(java.nio.file.Path path,
            java.util.List<String> corrections) {
        for (String corrected : corrections) {
            LOGGER.warn("Changed an invalid setting before saving to {}: "
                    + "{}.", path, corrected);
        }
    }
    private static final java.util.concurrent.atomic.AtomicBoolean backgroundSaveOut =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final java.util.concurrent.atomic.AtomicReference<dev.sandpaper.config.SandpaperConfig>
            backgroundSaveWaiting = new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicReference<Runnable>
            backgroundSaveWaitingFailure = new java.util.concurrent.atomic.AtomicReference<>();
    private static volatile boolean backgroundSaveResult = true;
    private static volatile java.util.function.UnaryOperator<Runnable>
            backgroundSaveFailureCarrier = java.util.function.UnaryOperator.identity();
    public static void carryBackgroundSaveFailures(
            java.util.function.UnaryOperator<Runnable> backToTheSaveThatStartedThem) {
        backgroundSaveFailureCarrier = backToTheSaveThatStartedThem == null
                ? java.util.function.UnaryOperator.identity() : backToTheSaveThatStartedThem;
    }
    public static boolean hasPendingBackgroundSave() {
        return backgroundSaveOut.get();
    }
    public static boolean lastBackgroundSaveSucceeded() {
        return backgroundSaveResult;
    }
    public static boolean saveInBackground(Runnable onFailure) {
        return saveInBackground(onFailure, workPool());
    }
    static boolean saveInBackground(Runnable onFailure, WorkPool pool) {
        Runnable carried = backgroundSaveFailureCarrier.apply(onFailure);
        Runnable failed = carried == null ? onFailure : carried;
        dev.sandpaper.core.CoreLog.setVerbose(config.verboseLogging);
        java.nio.file.Path path = configPath;
        if (path == null) {
            LOGGER.error("No settings path is set yet; "
                    + "nothing was saved.");
            failed.run();
            return false;
        }
        dev.sandpaper.config.SandpaperConfig live = config;
        if (live.unreadable != null) {
            LOGGER.error("Background save: {} "
                    + "could not be read at startup ({}); "
                    + "move or fix it and restart "
                    + "to save.",
                    path, live.unreadable);
            failed.run();
            return false;
        }
        reportSaveCorrections(path, live.normaliseAndListCorrections());
        dev.sandpaper.config.SandpaperConfig copy = new dev.sandpaper.config.SandpaperConfig();
        copy.copyFrom(live);
        synchronized (backgroundSaveOut) {
            if (backgroundSaveOut.get()) {
                backgroundSaveWaiting.set(copy);
                backgroundSaveWaitingFailure.set(failed);
                return true;
            }
            backgroundSaveOut.set(true);
            backgroundSaveResult = true;
        }
        return submitBackgroundSave(copy, path, failed, pool);
    }
    private static boolean submitBackgroundSave(dev.sandpaper.config.SandpaperConfig copy,
            java.nio.file.Path path, Runnable onFailure, WorkPool pool) {
        boolean queued = pool.submit(
                () -> writeBackgroundCopy(copy, path),
                result -> backgroundSaveLanded(result, path, onFailure, pool),
                () -> backgroundSaveDropped(path, onFailure, pool),
                result -> backgroundSaveLanded(result, path, onFailure, pool));
        if (queued) {
            return true;
        }
        boolean result = writeBackgroundCopy(copy, path);
        backgroundSaveLanded(result, path, onFailure, pool);
        return result;
    }
    private static boolean writeBackgroundCopy(dev.sandpaper.config.SandpaperConfig copy,
            java.nio.file.Path path) {
        try {
            copy.save(path);
            return true;
        } catch (java.io.IOException | RuntimeException failed) {
            LOGGER.error("could not write its settings to {}.", path, failed);
            return false;
        }
    }
    private static void backgroundSaveLanded(boolean result, java.nio.file.Path path,
            Runnable onFailure, WorkPool pool) {
        backgroundSaveFinished(result, path, onFailure, pool);
    }
    private static void backgroundSaveDropped(java.nio.file.Path path, Runnable onFailure,
            WorkPool pool) {
        backgroundSaveFinished(false, path, onFailure, pool);
    }
    private static void backgroundSaveFinished(boolean result, java.nio.file.Path path,
            Runnable onFailure, WorkPool pool) {
        dev.sandpaper.config.SandpaperConfig next;
        Runnable nextFailure;
        synchronized (backgroundSaveOut) {
            next = backgroundSaveWaiting.getAndSet(null);
            nextFailure = backgroundSaveWaitingFailure.getAndSet(null);
            if (next == null) {
                backgroundSaveResult = result;
                backgroundSaveOut.set(false);
            }
        }
        if (next == null && !result) {
            onFailure.run();
        }
        if (next != null) {
            submitBackgroundSave(next, path, nextFailure == null ? onFailure : nextFailure, pool);
        }
    }
    static void loadConfig(java.nio.file.Path path) {
        configPath = path;
        config = dev.sandpaper.config.SandpaperConfig.load(path);
        dev.sandpaper.core.CoreLog.setVerbose(config.verboseLogging);
        for (String corrected : config.corrections) {
            LOGGER.warn("Changed an invalid value in {}: "
                    + "{}.",
                    path, corrected);
        }
        if (config.unreadable != null) {
            LOGGER.error("{} could not be used ("
                    + "{}). Running on default settings; saving is "
                    + "refused until it is fixed or moved.", path, config.unreadable);
        }
        dev.sandpaper.config.SandpaperConfig.warmSerializer();
    }
    public static boolean isReady() {
        return workPool != null;
    }
    static synchronized void install(WorkPool p) {
        if (workPool != null) {
            LOGGER.warn("Already installed; ignoring the second install");
            return;
        }
        workPool = p;
        Thread watchdog = installWatchdog;
        if (watchdog != null) {
            watchdog.interrupt();
            installWatchdog = null;
        }
    }
    private static synchronized void armInstallWatchdog() {
        if (installWatchdog != null || workPool != null) {
            return;
        }
        Thread t = new Thread(Sandpaper::reportIfNeverInstalled, "sandpaper-install-watchdog");
        t.setDaemon(true);
        installWatchdog = t;
        t.start();
    }
    private static void reportIfNeverInstalled() {
        try {
            Thread.sleep(INSTALL_DEADLINE_MILLIS);
        } catch (InterruptedException installed) {
            return;
        }
        if (workPool != null) {
            return;
        }
        int jobs = SCHEDULER.registrations();
        if (jobs == 0) {
            return;
        }
        LOGGER.error("{} job(s) registered but the work pool was not installed "
                + "after {} ms; nothing runs. Check that "
                + "dev.sandpaper.SandpaperClient is a client entrypoint in "
                + "sandpaper's fabric.mod.json.", jobs, INSTALL_DEADLINE_MILLIS);
    }
}
