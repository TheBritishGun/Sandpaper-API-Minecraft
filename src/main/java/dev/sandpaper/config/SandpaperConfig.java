package dev.sandpaper.config;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
public final class SandpaperConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String DEFAULT_TREATMENT = "aero";
    public static final String LIQUID_TREATMENT = "liquid";
    public static final String CLEAR_TREATMENT = "clear";
    // Append only: a picker returns an index into this list.
    public static final List<String> TREATMENTS =
            List.of(DEFAULT_TREATMENT, LIQUID_TREATMENT, CLEAR_TREATMENT);
    // Shared by both colour pickers; append only, same reason as TREATMENTS.
    public static final String DEFAULT_COLOUR = "default";
    public static final List<String> COLOURS = List.of(DEFAULT_COLOUR, "blue", "red", "green",
            "purple", "amber", "royal_purple", "pastel_pink", "custom");
    public static final String DEFAULT_FONT_ID = "";
    public static final FontScope DEFAULT_FONT_SCOPE = FontScope.LETTERING;
    public int workerThreads = 0;
    public int queueCapacity = 256;
    public double renderShare = 1.0 / 3.0;
    public double tickShare = 1.0 / 8.0;
    public int floorMicros = 250;
    public int renderCeilingMicros = 4_000;
    public int tickCeilingMicros = 3_000;
    public int applyPerTick = 8;
    public boolean debugReadout = false;
    // Adds detail only; never silences errors.
    public boolean verboseLogging = false;
    public String treatment = DEFAULT_TREATMENT;
    public String themeColour = DEFAULT_COLOUR;
    public String themeCustom = "";
    public String accentColour = DEFAULT_COLOUR;
    public String accentCustom = "";
    public String fontId = DEFAULT_FONT_ID;
    public FontScope fontScope = DEFAULT_FONT_SCOPE;
    public int fontSizePercent = 100;
    public float fontOversample = DEFAULT_FONT_OVERSAMPLE;
    public transient List<String> corrections = List.of();
    // Why the load failed; null on success.
    public transient String unreadable;
    // Ceiling for a manual override; normalise() clamps to this.
    public static final int MAX_WORKER_THREADS = 32;
    public static final int MIN_WORKER_THREADS = 0;
    public static boolean overrideCeilingReachesAutoPick(int maxWorkerThreads, int autoPickCap) {
        return maxWorkerThreads >= autoPickCap;
    }
    public static final int MIN_QUEUE = 16;
    public static final int MAX_QUEUE = 4096;
    public static final int MIN_FLOOR_MICROS = 50;
    public static final int MAX_FLOOR_MICROS = 4_000;
    public static final int MIN_CEILING_MICROS = 500;
    public static final int MAX_CEILING_MICROS = 20_000;
    public static final int MIN_APPLY = 1;
    public static final int MAX_APPLY = 256;
    public static final double MIN_SHARE = 0.01;
    public static final double MAX_SHARE = 1.0;
    public static final int MIN_FONT_SIZE_PERCENT = 25;
    public static final int MAX_FONT_SIZE_PERCENT = 300;
    public static final float MIN_FONT_OVERSAMPLE = 1.0f;
    public static final float MAX_FONT_OVERSAMPLE = 4.0f;
    private static final float DEFAULT_FONT_OVERSAMPLE = 2.0f;
    static double clampShare(double value) {
        return Math.clamp(value, MIN_SHARE, MAX_SHARE);
    }
    static int clampCeiling(int value) {
        return Math.clamp(value, MIN_CEILING_MICROS, MAX_CEILING_MICROS);
    }
    public static float normaliseFontOversample(float value) {
        if (Float.isNaN(value)) {
            return DEFAULT_FONT_OVERSAMPLE;
        }
        return Math.clamp(value, MIN_FONT_OVERSAMPLE, MAX_FONT_OVERSAMPLE);
    }
    public static String normaliseTreatment(String value) {
        if (isLiquidTreatment(value)) {
            return LIQUID_TREATMENT;
        }
        return value != null && TREATMENTS.contains(value) ? value : DEFAULT_TREATMENT;
    }
    // An id off the list becomes default; the paired custom text is untouched.
    public static String normaliseColour(String value) {
        return value != null && COLOURS.contains(value) ? value : DEFAULT_COLOUR;
    }
    public static String normaliseFontId(String value) {
        return value == null ? DEFAULT_FONT_ID : value;
    }
    public static FontScope normaliseFontScope(FontScope value) {
        return value == null ? DEFAULT_FONT_SCOPE : value;
    }
    public static int normaliseFontSizePercent(int value) {
        return Math.clamp(value, MIN_FONT_SIZE_PERCENT, MAX_FONT_SIZE_PERCENT);
    }
    public SandpaperConfig normalise() {
        workerThreads = Math.clamp(workerThreads, MIN_WORKER_THREADS, MAX_WORKER_THREADS);
        queueCapacity = Math.clamp(queueCapacity, MIN_QUEUE, MAX_QUEUE);
        if (Double.isNaN(renderShare)) {
            renderShare = new SandpaperConfig().renderShare;
        }
        if (Double.isNaN(tickShare)) {
            tickShare = new SandpaperConfig().tickShare;
        }
        renderShare = clampShare(renderShare);
        tickShare = clampShare(tickShare);
        floorMicros = Math.clamp(floorMicros, MIN_FLOOR_MICROS, MAX_FLOOR_MICROS);
        renderCeilingMicros = clampCeiling(renderCeilingMicros);
        tickCeilingMicros = clampCeiling(tickCeilingMicros);
        applyPerTick = Math.clamp(applyPerTick, MIN_APPLY, MAX_APPLY);
        treatment = normaliseTreatment(treatment);
        themeColour = normaliseColour(themeColour);
        accentColour = normaliseColour(accentColour);
        fontId = normaliseFontId(fontId);
        fontScope = normaliseFontScope(fontScope);
        fontSizePercent = normaliseFontSizePercent(fontSizePercent);
        fontOversample = normaliseFontOversample(fontOversample);
        floorMicros = normalisedFloorMicros();
        return this;
    }
    public boolean liquidTreatment() {
        return isLiquidTreatment(treatment);
    }
    private static boolean isLiquidTreatment(String value) {
        return LIQUID_TREATMENT.equals(value);
    }
    public int normalisedFloorMicros() {
        return Math.min(floorMicros, Math.min(renderCeilingMicros, tickCeilingMicros));
    }
    public long floorNanos() {
        return floorMicros * 1_000L;
    }
    public long renderCeilingNanos() {
        return renderCeilingMicros * 1_000L;
    }
    public long tickCeilingNanos() {
        return tickCeilingMicros * 1_000L;
    }
    public void copyFrom(SandpaperConfig other) {
        this.workerThreads = other.workerThreads;
        this.queueCapacity = other.queueCapacity;
        this.renderShare = other.renderShare;
        this.tickShare = other.tickShare;
        this.floorMicros = other.floorMicros;
        this.renderCeilingMicros = other.renderCeilingMicros;
        this.tickCeilingMicros = other.tickCeilingMicros;
        this.applyPerTick = other.applyPerTick;
        this.debugReadout = other.debugReadout;
        this.verboseLogging = other.verboseLogging;
        this.treatment = other.treatment;
        this.themeColour = other.themeColour;
        this.themeCustom = other.themeCustom;
        this.accentColour = other.accentColour;
        this.accentCustom = other.accentCustom;
        this.fontId = other.fontId;
        this.fontScope = other.fontScope;
        this.fontSizePercent = other.fontSizePercent;
        this.fontOversample = other.fontOversample;
        this.unreadable = other.unreadable;
    }
    private static List<String> note(List<String> changed, String where, String field,
            int wasValue, int isValue) {
        return wasValue == isValue ? changed
                : append(changed, field + " was " + wasValue + where + " and is " + isValue);
    }
    private static List<String> note(List<String> changed, String where, String field,
            double wasValue, double isValue) {
        return Double.doubleToLongBits(wasValue) == Double.doubleToLongBits(isValue) ? changed
                : append(changed, field + " was " + wasValue + where + " and is " + isValue);
    }
    private static List<String> note(List<String> changed, String where, String field,
            float wasValue, float isValue) {
        return Float.floatToIntBits(wasValue) == Float.floatToIntBits(isValue) ? changed
                : append(changed, field + " was " + wasValue + where + " and is " + isValue);
    }
    private static List<String> note(List<String> changed, String where, String field,
            Object wasValue, Object isValue) {
        return Objects.equals(wasValue, isValue) ? changed
                : append(changed, field + " was " + wasValue + where + " and is " + isValue);
    }
    private static List<String> append(List<String> changed, String line) {
        List<String> into = changed == null ? new ArrayList<>() : changed;
        into.add(line);
        return into;
    }
    public List<String> normaliseAndListCorrections() {
        return normaliseAndListCorrections("");
    }
    private List<String> normaliseAndListCorrections(String where) {
        int workerThreadsWas = workerThreads;
        int queueCapacityWas = queueCapacity;
        double renderShareWas = renderShare;
        double tickShareWas = tickShare;
        int floorMicrosWas = floorMicros;
        int renderCeilingMicrosWas = renderCeilingMicros;
        int tickCeilingMicrosWas = tickCeilingMicros;
        int applyPerTickWas = applyPerTick;
        String treatmentWas = treatment;
        String themeColourWas = themeColour;
        String accentColourWas = accentColour;
        String fontIdWas = fontId;
        FontScope fontScopeWas = fontScope;
        int fontSizePercentWas = fontSizePercent;
        float fontOversampleWas = fontOversample;
        normalise();
        List<String> changed = null;
        changed = note(changed, where, "workerThreads", workerThreadsWas, workerThreads);
        changed = note(changed, where, "queueCapacity", queueCapacityWas, queueCapacity);
        changed = note(changed, where, "renderShare", renderShareWas, renderShare);
        changed = note(changed, where, "tickShare", tickShareWas, tickShare);
        changed = note(changed, where, "floorMicros", floorMicrosWas, floorMicros);
        changed = note(changed, where, "renderCeilingMicros", renderCeilingMicrosWas,
                renderCeilingMicros);
        changed = note(changed, where, "tickCeilingMicros", tickCeilingMicrosWas,
                tickCeilingMicros);
        changed = note(changed, where, "applyPerTick", applyPerTickWas, applyPerTick);
        changed = note(changed, where, "treatment", treatmentWas, treatment);
        changed = note(changed, where, "themeColour", themeColourWas, themeColour);
        changed = note(changed, where, "accentColour", accentColourWas, accentColour);
        changed = note(changed, where, "fontId", fontIdWas, fontId);
        changed = note(changed, where, "fontScope", fontScopeWas, fontScope);
        changed = note(changed, where, "fontSizePercent", fontSizePercentWas, fontSizePercent);
        changed = note(changed, where, "fontOversample", fontOversampleWas, fontOversample);
        return changed == null ? List.of() : List.copyOf(changed);
    }
    public static SandpaperConfig load(Path path) {
        if (path == null || Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return new SandpaperConfig();
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return refusing(new SandpaperConfig(), "it is not a regular file");
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            SandpaperConfig loaded = GSON.fromJson(reader, SandpaperConfig.class);
            if (loaded == null) {
                return refusing(new SandpaperConfig(), "it holds no document");
            }
            loaded.corrections = loaded.normaliseAndListCorrections(" in the file");
            return loaded;
        } catch (IOException unreadable) {
            return refusing(new SandpaperConfig(), "it could not be read (" + unreadable + ")");
        } catch (JsonParseException malformed) {
            return refusing(new SandpaperConfig(), "it is not valid JSON (" + malformed + ")");
        }
    }
    private static SandpaperConfig refusing(SandpaperConfig defaults, String why) {
        defaults.unreadable = why;
        return defaults;
    }
    public void save(Path path) throws IOException {
        if (unreadable != null) {
            throw new IOException(path + " could not be read (" + unreadable
                    + "); saving would delete whatever is in it. "
                    + "Move or fix it and restart to save.");
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        }
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    // Warms the Gson write path on the client thread at boot; never throws.
    public static void warmSerializer() {
        try {
            GSON.toJson(new SandpaperConfig(), Writer.nullWriter());
        } catch (RuntimeException illGson) {
        }
    }
}
