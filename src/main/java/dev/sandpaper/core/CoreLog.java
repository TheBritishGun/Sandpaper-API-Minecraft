package dev.sandpaper.core;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
public final class CoreLog {
    public static final String STANDARD_ERROR_PREFIX = "[sandpaper] ";
    static final BiConsumer<String, Throwable> STANDARD_ERROR = CoreLog::toStandardError;
    static final Consumer<String> VERBOSE_STANDARD_ERROR = CoreLog::verboseToStandardError;
    private static volatile BiConsumer<String, Throwable> sink = STANDARD_ERROR;
    private static volatile Consumer<String> verboseSink = VERBOSE_STANDARD_ERROR;
    private static volatile boolean verboseWanted = false;
    // Limits the broken-sink report to once; the sink is still tried.
    private static volatile boolean verboseSinkBroken = false;
    // Lines to skip after a failure; a countdown, not a permanent latch.
    private static volatile int verboseSinkSkipsLeft = 0;
    static final int VERBOSE_SINK_COOLDOWN_LINES = 1_000;
    private CoreLog() {
    }
    // Sends all reports here instead of stderr; the last caller wins.
    public static void install(BiConsumer<String, Throwable> where) {
        sink = Objects.requireNonNull(where, "where");
    }
    // Sends verbose detail here instead of stderr; independent of install()'s sink.
    public static void installVerbose(Consumer<String> where) {
        verboseSink = Objects.requireNonNull(where, "where");
        verboseSinkBroken = false;
        verboseSinkSkipsLeft = 0;
    }
    // Turns verbose logging on or off; ends any cooldown. Faults are reported regardless.
    public static void setVerbose(boolean on) {
        verboseSinkSkipsLeft = 0;
        verboseWanted = on;
    }
    // Whether verbose detail is wanted; check before building an expensive message.
    public static boolean isVerbose() {
        return verboseWanted;
    }
    static void error(String message, Throwable thrown) {
        try {
            sink.accept(message, thrown);
        } catch (RuntimeException | Error broken) {
            try {
                toStandardError(message, thrown);
                toStandardError("the installed CoreLog sink threw; the line above "
                        + "went to standard error, not your log", broken);
            } catch (RuntimeException | Error nowhereLeft) {
            }
        }
    }
    static void verbose(String message) {
        if (!verboseWanted) {
            return;
        }
        int skipsLeft = verboseSinkSkipsLeft;
        if (skipsLeft > 0) {
            verboseSinkSkipsLeft = skipsLeft - 1;
            return;
        }
        try {
            verboseSink.accept(message);
        } catch (RuntimeException | Error broken) {
            verboseSinkSkipsLeft = VERBOSE_SINK_COOLDOWN_LINES;
            if (!verboseSinkBroken) {
                verboseSinkBroken = true;
                error("the installed CoreLog verbose sink threw; verbose "
                        + "detail is skipped for the next "
                        + VERBOSE_SINK_COOLDOWN_LINES + " lines before it retries. "
                        + "Errors are unaffected.", broken);
            }
        }
    }
    private static void toStandardError(String message, Throwable thrown) {
        System.err.println(STANDARD_ERROR_PREFIX + message);
        if (thrown != null) {
            thrown.printStackTrace();
        }
    }
    private static void verboseToStandardError(String message) {
        System.err.println(STANDARD_ERROR_PREFIX + "verbose: " + message);
    }
}
