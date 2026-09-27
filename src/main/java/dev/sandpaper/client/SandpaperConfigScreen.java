package dev.sandpaper.client;
import dev.sandpaper.Sandpaper;
import dev.sandpaper.api.settings.OptionSpec;
import dev.sandpaper.api.settings.SettingsContributor;
import dev.sandpaper.api.settings.SettingsGroup;
import dev.sandpaper.api.settings.SettingsPage;
import dev.sandpaper.client.font.FontPicker;
import dev.sandpaper.client.font.SandpaperFonts;
import dev.sandpaper.client.gui.AeroConfigScreen;
import dev.sandpaper.client.gui.AeroPainter;
import dev.sandpaper.client.gui.AeroTheme;
import dev.sandpaper.client.gui.SettingsContributions;
import dev.sandpaper.client.gui.options.Opt;
import dev.sandpaper.config.FontScope;
import dev.sandpaper.config.SandpaperConfig;
import dev.sandpaper.core.WorkPool;
import dev.sandpaper.font.FontLibrary.Face;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Rows write straight into the live config; Escape calls no sink.
public final class SandpaperConfigScreen implements SettingsContributor {
    private static final Logger LOGGER = LoggerFactory.getLogger(Sandpaper.MOD_ID);
    // One id: a second failed save replaces the notice instead of stacking.
    private static final SystemToast.SystemToastId SAVE_FAILED_TOAST =
            new SystemToast.SystemToastId();
    private static final SystemToast.SystemToastId FONT_PICK_DROPPED_TOAST =
            new SystemToast.SystemToastId();
    // Min share slider percent, derived from SandpaperConfig.MIN_SHARE.
    private static final int MIN_SHARE_PERCENT =
            sharePercent(SandpaperConfig.MIN_SHARE, Integer.MIN_VALUE, Integer.MAX_VALUE);
    // Top of both share sliders.
    private static final int MAX_SHARE_PERCENT =
            sharePercent(SandpaperConfig.MAX_SHARE, Integer.MIN_VALUE, Integer.MAX_VALUE);
    // Floor slider snap increment, in microseconds.
    private static final int FLOOR_STEP = 50;
    // Ceiling slider snap increment, in microseconds.
    private static final int CEILING_STEP = 250;
    // Queue slider snap increment, in jobs.
    private static final int QUEUE_STEP = 16;
    private static final SandpaperConfig DEFAULT_CONFIG = new SandpaperConfig();
    private static final Identifier SCHEDULING_ID =
            Identifier.fromNamespaceAndPath(Sandpaper.MOD_ID, "scheduling");
    private static final Identifier LETTERING_ID =
            Identifier.fromNamespaceAndPath(Sandpaper.MOD_ID, "lettering");
    private static final Identifier APPEARANCE_ID =
            Identifier.fromNamespaceAndPath(Sandpaper.MOD_ID, "appearance");
    private static final int FONT_SIZE_STEP = 5;
    private static final float OVERSAMPLE_STEP = 0.5f;
    // The treatment the open screen draws in; not the saved one until Apply.
    private static AeroTheme.Treatment stagedTreatment = AeroTheme.Treatment.AERO;
    // Index into SandpaperConfig.COLOURS for the staged theme and accent rows.
    private static int stagedThemeIndex = SandpaperConfig.COLOURS.indexOf(
            SandpaperConfig.DEFAULT_COLOUR);
    private static int stagedAccentIndex = SandpaperConfig.COLOURS.indexOf(
            SandpaperConfig.DEFAULT_COLOUR);
    // Called reflectively by the mod loader.
    public SandpaperConfigScreen() {
    }
    // Opens the shared settings screen. The title names no mod.
    public static Screen create(Screen parent) {
        SandpaperConfig live = Sandpaper.config();
        stagedTreatment = treatmentNamed(live.treatment);
        stagedThemeIndex = SandpaperConfig.COLOURS.indexOf(
                SandpaperConfig.normaliseColour(live.themeColour));
        stagedAccentIndex = SandpaperConfig.COLOURS.indexOf(
                SandpaperConfig.normaliseColour(live.accentColour));
        AeroPainter.setTreatmentSupplier(() -> stagedTreatment);
        return new AeroConfigScreen(parent,
                Component.translatable("sandpaper.screen.title"),
                SandpaperFonts::lettering);
    }
    // Sandpaper's page, read live; id namespaced to sandpaper's own mod id.
    @Override
    public List<SettingsPage> pages() {
        SandpaperConfig live = Sandpaper.config();
        return List.of(
                new SettingsPage(
                        SCHEDULING_ID,
                        Component.translatable("sandpaper.config.category.scheduling"),
                        // Budgets and threads are not on this screen; SandpaperConfig.pinToDefaults holds them.
                        List.of(diagnostics(live, DEFAULT_CONFIG))),
                new SettingsPage(
                        LETTERING_ID,
                        Component.translatable("sandpaper.config.category.lettering"),
                        List.of(lettering(live, DEFAULT_CONFIG,
                                SandpaperFonts.fonts().faces()))),
                new SettingsPage(
                        APPEARANCE_ID,
                        Component.translatable("sandpaper.config.category.appearance"),
                        List.of(appearance(live, DEFAULT_CONFIG))));
    }
    // True when the write was accepted; a background write may still be running.
    @Override
    public boolean save() {
        try {
            return Sandpaper.saveInBackground(SandpaperConfigScreen::reportSaveFailed);
        } finally {
            SandpaperFonts.settingsSaved();
        }
    }
    private static void reportSaveFailed() {
        if (raiseToast(SAVE_FAILED_TOAST,
                Component.translatableWithFallback("sandpaper.toast.save_failed.title",
                        "Sandpaper settings not saved"),
                Component.translatableWithFallback("sandpaper.toast.save_failed.body",
                        "Not saved. Check the log"))) {
            SettingsContributions.reportedOwnSaveFailure();
        }
    }
    private static boolean raiseToast(SystemToast.SystemToastId id, Component title,
                                      Component body) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return false;
        }
        SystemToast.addOrUpdate(client.gui.toastManager(), id, title, body);
        return true;
    }
    private static final class StagedBudget {
        private int stagedFloor;
        private int stagedRenderCeiling;
        private int stagedTickCeiling;
        StagedBudget(int stagedFloor, int stagedRenderCeiling, int stagedTickCeiling) {
            this.stagedFloor = stagedFloor;
            this.stagedRenderCeiling = stagedRenderCeiling;
            this.stagedTickCeiling = stagedTickCeiling;
        }
    }
    private static final Component RENDER_SHARE_NAME =
            Component.translatable("sandpaper.option.render_share");
    private static final Component RENDER_SHARE_DESC =
            Component.translatable("sandpaper.option.render_share.desc");
    private static final Component TICK_SHARE_NAME =
            Component.translatable("sandpaper.option.tick_share");
    private static final Component TICK_SHARE_DESC =
            Component.translatable("sandpaper.option.tick_share.desc");
    private static SettingsGroup budgets(SandpaperConfig c, SandpaperConfig defaults) {
        // The floor bound recomputes from the ceilings' current staged values.
        StagedBudget staged = new StagedBudget(c.floorMicros, c.renderCeilingMicros,
                c.tickCeilingMicros);
        return new SettingsGroup(
                Component.translatable("sandpaper.config.group.budgets"),
                Component.translatable("sandpaper.config.group.budgets.desc"),
                List.of(
                        shareRow(RENDER_SHARE_NAME,
                                RENDER_SHARE_DESC,
                                defaults.renderShare,
                                () -> c.renderShare,
                                v -> c.renderShare = v),
                        shareRow(TICK_SHARE_NAME,
                                TICK_SHARE_DESC,
                                defaults.tickShare,
                                () -> c.tickShare,
                                v -> c.tickShare = v),
                        // Bounded by both ceilings.
                        new OptionSpec.IntSlider(
                                Component.translatable("sandpaper.option.floor"),
                                Component.translatable("sandpaper.option.floor.desc"),
                                defaults.floorMicros,
                                c.floorMicros,
                                v -> c.floorMicros = v,
                                SandpaperConfig.MIN_FLOOR_MICROS,
                                SandpaperConfig.MAX_FLOOR_MICROS,
                                FLOOR_STEP,
                                null,
                                v -> staged.stagedFloor = v,
                                null,
                                () -> Math.min(staged.stagedFloor,
                                        floorSliderMax(staged.stagedRenderCeiling,
                                                staged.stagedTickCeiling,
                                                SandpaperConfig.MIN_FLOOR_MICROS,
                                                SandpaperConfig.MAX_FLOOR_MICROS))),
                        new OptionSpec.IntSlider(
                                Component.translatable("sandpaper.option.render_ceiling"),
                                Component.translatable("sandpaper.option.render_ceiling.desc"),
                                defaults.renderCeilingMicros,
                                c.renderCeilingMicros,
                                v -> c.renderCeilingMicros = v,
                                SandpaperConfig.MIN_CEILING_MICROS,
                                SandpaperConfig.MAX_CEILING_MICROS, CEILING_STEP,
                                null,
                                v -> staged.stagedRenderCeiling = v),
                        new OptionSpec.IntSlider(
                                Component.translatable("sandpaper.option.tick_ceiling"),
                                Component.translatable("sandpaper.option.tick_ceiling.desc"),
                                defaults.tickCeilingMicros,
                                c.tickCeilingMicros,
                                v -> c.tickCeilingMicros = v,
                                SandpaperConfig.MIN_CEILING_MICROS,
                                SandpaperConfig.MAX_CEILING_MICROS, CEILING_STEP,
                                null,
                                v -> staged.stagedTickCeiling = v),
                        new OptionSpec.IntSlider(
                                Component.translatable("sandpaper.option.apply_per_tick"),
                                Component.translatable("sandpaper.option.apply_per_tick.desc"),
                                defaults.applyPerTick,
                                c.applyPerTick,
                                v -> c.applyPerTick = v,
                                SandpaperConfig.MIN_APPLY, SandpaperConfig.MAX_APPLY, 1)));
    }
    // Must mirror normalise()'s clamp exactly.
    static int floorSliderMax(int renderCeilingMicros, int tickCeilingMicros,
                              int sliderMin, int saveClamp) {
        return Math.clamp(Math.min(renderCeilingMicros, tickCeilingMicros),
                sliderMin, saveClamp);
    }
    private static SettingsGroup threads(SandpaperConfig c, SandpaperConfig defaults) {
        // availableProcessors() can disagree between calls.
        int processors = Runtime.getRuntime().availableProcessors();
        boolean[] workerTouched = {false};
        return new SettingsGroup(
                Component.translatable("sandpaper.config.group.threads"),
                Component.translatable("sandpaper.config.group.threads.desc",
                        WorkPool.defaultThreads(), processors),
                List.of(
                        new OptionSpec.IntSlider(
                                Component.translatable("sandpaper.option.worker_threads"),
                                Component.translatable("sandpaper.option.worker_threads.desc"),
                                defaults.workerThreads,
                                c.workerThreads,
                                v -> c.workerThreads = workerThreadsToStore(v,
                                        c.workerThreads, workerTouched[0]),
                                SandpaperConfig.MIN_WORKER_THREADS,
                                workerThreadSliderMax(processors,
                                        SandpaperConfig.MAX_WORKER_THREADS),
                                1,
                                null,
                                v -> workerTouched[0] = true,
                                null,
                                null),
                        new OptionSpec.IntSlider(
                                Component.translatable("sandpaper.option.queue_capacity"),
                                Component.translatable("sandpaper.option.queue_capacity.desc"),
                                defaults.queueCapacity,
                                c.queueCapacity,
                                v -> c.queueCapacity = v,
                                SandpaperConfig.MIN_QUEUE, SandpaperConfig.MAX_QUEUE,
                                QUEUE_STEP)));
    }
    // Floor of 2, not 1.
    static int workerThreadSliderMax(int processors, int saveClamp) {
        return Math.min(saveClamp, Math.max(2, processors));
    }
    // touched tells an edited value from an unedited one.
    static int workerThreadsToStore(int staged, int current, boolean touched) {
        return touched ? staged : current;
    }
    private static SettingsGroup diagnostics(SandpaperConfig c, SandpaperConfig defaults) {
        return new SettingsGroup(
                Component.translatable("sandpaper.config.group.diagnostics"),
                List.of(
                        new OptionSpec.Toggle(
                                Component.translatable("sandpaper.option.debug_readout"),
                                Component.translatable("sandpaper.option.debug_readout.desc"),
                                defaults.debugReadout,
                                c.debugReadout,
                                v -> c.debugReadout = v),
                        // Adds detail only; errors are logged either way.
                        new OptionSpec.Toggle(
                                Component.translatable("sandpaper.option.verbose_logging"),
                                Component.translatable("sandpaper.option.verbose_logging.desc"),
                                defaults.verboseLogging,
                                c.verboseLogging,
                                v -> c.verboseLogging = v)));
    }
    private static SettingsGroup appearance(SandpaperConfig c, SandpaperConfig defaults) {
        List<String> treatments = SandpaperConfig.TREATMENTS;
        List<String> colours = SandpaperConfig.COLOURS;
        List<Component> colourLabels = colourLabels(colours);
        int customIndex = colours.indexOf("custom");
        return new SettingsGroup(
                Component.translatable("sandpaper.config.group.appearance"),
                List.of(
                        new OptionSpec.Choice(
                                Component.translatable("sandpaper.option.treatment"),
                                Component.translatable("sandpaper.option.treatment.desc"),
                                List.of(Component.translatable("sandpaper.option.treatment.aero"),
                                        Component.translatable("sandpaper.option.treatment.liquid"),
                                        Component.translatable("sandpaper.option.treatment.clear")),
                                treatments.indexOf(SandpaperConfig.normaliseTreatment(
                                        defaults.treatment)),
                                treatments.indexOf(SandpaperConfig.normaliseTreatment(c.treatment)),
                                index -> {
                                    c.treatment = treatments.get(index);
                                    stageTreatment(treatments, index);
                                },
                                null,
                                index -> stageTreatment(treatments, index)),
                        new OptionSpec.Choice(
                                Component.translatable("sandpaper.option.theme_colour"),
                                Component.translatable("sandpaper.option.theme_colour.desc"),
                                colourLabels,
                                colours.indexOf(SandpaperConfig.normaliseColour(
                                        defaults.themeColour)),
                                colours.indexOf(SandpaperConfig.normaliseColour(c.themeColour)),
                                index -> {
                                    c.themeColour = colours.get(index);
                                    stagedThemeIndex = index;
                                    applyColours(c);
                                },
                                null,
                                index -> stagedThemeIndex = index),
                        new OptionSpec.Colour(
                                Component.translatable("sandpaper.option.theme_colour_custom"),
                                Component.translatable("sandpaper.option.theme_colour_custom.desc"),
                                0xFF46DCC6,
                                blankAsNull(c.themeCustom),
                                value -> {
                                    c.themeCustom = value;
                                    applyColours(c);
                                },
                                () -> stagedThemeIndex == customIndex,
                                null),
                        new OptionSpec.Choice(
                                Component.translatable("sandpaper.option.accent_colour"),
                                Component.translatable("sandpaper.option.accent_colour.desc"),
                                colourLabels,
                                colours.indexOf(SandpaperConfig.normaliseColour(
                                        defaults.accentColour)),
                                colours.indexOf(SandpaperConfig.normaliseColour(c.accentColour)),
                                index -> {
                                    c.accentColour = colours.get(index);
                                    stagedAccentIndex = index;
                                    applyColours(c);
                                },
                                null,
                                index -> stagedAccentIndex = index),
                        new OptionSpec.Colour(
                                Component.translatable("sandpaper.option.accent_colour_custom"),
                                Component.translatable("sandpaper.option.accent_colour_custom.desc"),
                                0xFF46DCC6,
                                blankAsNull(c.accentCustom),
                                value -> {
                                    c.accentCustom = value;
                                    applyColours(c);
                                },
                                () -> stagedAccentIndex == customIndex,
                                null)));
    }
    private static String blankAsNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
    private static void stageTreatment(List<String> treatments, int index) {
        stagedTreatment = treatmentNamed(treatments.get(index));
    }
    private static List<Component> colourLabels(List<String> colours) {
        List<Component> labels = new ArrayList<>(colours.size());
        for (String id : colours) {
            labels.add(colourLabel(id));
        }
        return labels;
    }
    private static Component colourLabel(String id) {
        return switch (id) {
            case "default" -> Component.translatable("sandpaper.option.colour.default");
            case "blue" -> Component.translatable("sandpaper.option.colour.blue");
            case "red" -> Component.translatable("sandpaper.option.colour.red");
            case "green" -> Component.translatable("sandpaper.option.colour.green");
            case "purple" -> Component.translatable("sandpaper.option.colour.purple");
            case "amber" -> Component.translatable("sandpaper.option.colour.amber");
            case "royal_purple" -> Component.translatable("sandpaper.option.colour.royal_purple");
            case "pastel_pink" -> Component.translatable("sandpaper.option.colour.pastel_pink");
            case "custom" -> Component.translatable("sandpaper.option.colour.custom");
            default -> Component.literal(id);
        };
    }
    // The one place a saved colour becomes one AeroTheme can draw with; called from sinks, never onChange.
    public static void applyColours(SandpaperConfig c) {
        AeroTheme.useColours(resolveColour(c.themeColour, c.themeCustom),
                resolveColour(c.accentColour, c.accentCustom));
    }
    private static int resolveColour(String id, String customText) {
        String chosen = SandpaperConfig.normaliseColour(id);
        if ("custom".equals(chosen)) {
            Opt.Swatch swatch = Opt.Swatch.parse(customText, 0xFF);
            return swatch == null || swatch.preset() == Opt.Swatch.Preset.DEFAULT
                    ? AeroTheme.SHIPPED_COLOUR
                    : swatch.resolve(0xFF46DCC6) & 0xFFFFFF;
        }
        return presetRgb(chosen);
    }
    // The approved samples, as RGB; keep in step with SandpaperConfig.COLOURS.
    private static int presetRgb(String id) {
        return switch (id) {
            case "blue" -> 0x3B82F6;
            case "red" -> 0xE0453A;
            case "green" -> 0x4CAF50;
            case "purple" -> 0x9B59D0;
            case "amber" -> 0xE0A33A;
            case "royal_purple" -> 0x7851A9;
            case "pastel_pink" -> 0xFFD1DC;
            default -> AeroTheme.SHIPPED_COLOUR;
        };
    }
    // Converts a saved treatment name to a drawing treatment; an unknown name falls back to Aero.
    private static AeroTheme.Treatment treatmentNamed(String name) {
        String chosen = SandpaperConfig.normaliseTreatment(name);
        if (SandpaperConfig.LIQUID_TREATMENT.equals(chosen)) {
            return AeroTheme.Treatment.LIQUID;
        }
        if (SandpaperConfig.CLEAR_TREATMENT.equals(chosen)) {
            return AeroTheme.Treatment.CLEAR;
        }
        return AeroTheme.Treatment.AERO;
    }
    private static SettingsGroup lettering(SandpaperConfig c, SandpaperConfig defaults,
                                           List<Face> faces) {
        String stored = SandpaperConfig.normaliseFontId(c.fontId);
        List<String> ids = faceIds(faces, stored);
        // Staged face for this screen; not written into c until Apply.
        String[] chosen = {stored};
        BooleanSupplier picked = () -> !chosen[0].isEmpty();
        List<FontScope> scopes = List.of(FontScope.values());
        return new SettingsGroup(
                Component.translatable("sandpaper.config.group.font"),
                List.of(
                        new OptionSpec.Choice(
                                Component.translatable("sandpaper.option.font_face"),
                                Component.translatable("sandpaper.option.font_face.desc"),
                                faceLabels(faces, ids),
                                ids.indexOf(""),
                                ids.indexOf(stored),
                                index -> c.fontId = ids.get(index),
                                null,
                                index -> chosen[0] = ids.get(index)),
                        new OptionSpec.Choice(
                                Component.translatable("sandpaper.option.font_scope"),
                                Component.translatable("sandpaper.option.font_scope.desc"),
                                scopeLabels(scopes),
                                scopes.indexOf(defaults.fontScope),
                                scopes.indexOf(SandpaperConfig.normaliseFontScope(c.fontScope)),
                                index -> c.fontScope = scopes.get(index),
                                picked,
                                null),
                        new OptionSpec.IntSlider(
                                Component.translatable("sandpaper.option.font_size"),
                                Component.translatable("sandpaper.option.font_size.desc"),
                                defaults.fontSizePercent,
                                c.fontSizePercent,
                                v -> c.fontSizePercent = v,
                                SandpaperConfig.MIN_FONT_SIZE_PERCENT,
                                SandpaperConfig.MAX_FONT_SIZE_PERCENT,
                                FONT_SIZE_STEP,
                                picked,
                                null),
                        new OptionSpec.FloatSlider(
                                Component.translatable("sandpaper.option.font_oversample"),
                                Component.translatable("sandpaper.option.font_oversample.desc"),
                                defaults.fontOversample,
                                c.fontOversample,
                                v -> c.fontOversample = v,
                                SandpaperConfig.MIN_FONT_OVERSAMPLE,
                                SandpaperConfig.MAX_FONT_OVERSAMPLE,
                                OVERSAMPLE_STEP,
                                picked,
                                null),
                        new OptionSpec.Button(
                                Component.translatable("sandpaper.option.font_add"),
                                Component.translatable("sandpaper.option.font_add.desc"),
                                Component.translatable("sandpaper.option.font_add.button"),
                                () -> {
                                    AeroConfigScreen requestedBy = currentConfigScreen();
                                    FontPicker.pick(SandpaperFonts.fonts(),
                                            SandpaperFonts::invalidateLettering,
                                            id -> adoptFont(c, requestedBy, id));
                                },
                                FontPicker::available),
                        new OptionSpec.Button(
                                Component.translatable("sandpaper.option.font_folder"),
                                Component.translatable("sandpaper.option.font_folder.desc"),
                                Component.translatable("sandpaper.option.font_folder.button"),
                                () -> FontPicker.openFolder(SandpaperFonts.fontDirectory()))));
    }
    private static List<String> faceIds(List<Face> faces, String stored) {
        List<String> ids = new ArrayList<>(faces.size() + 2);
        ids.add("");
        for (Face face : faces) {
            ids.add(face.id());
        }
        if (!ids.contains(stored)) {
            ids.add(stored);
        }
        return List.copyOf(ids);
    }
    private static List<Component> faceLabels(List<Face> faces, List<String> ids) {
        List<Component> labels = new ArrayList<>(ids.size());
        for (String id : ids) {
            labels.add(faceLabel(faces, id));
        }
        return labels;
    }
    private static Component faceLabel(List<Face> faces, String id) {
        if (id.isEmpty()) {
            return Component.translatable("sandpaper.option.font_face.default");
        }
        if (!SandpaperFonts.usableFace(id)) {
            return Component.translatableWithFallback("sandpaper.option.font_face.unavailable",
                    "unavailable: %s", id);
        }
        for (Face face : faces) {
            if (face.id().equals(id)) {
                return Component.literal(face.displayName());
            }
        }
        return Component.literal(id);
    }
    private static List<Component> scopeLabels(List<FontScope> scopes) {
        List<Component> labels = new ArrayList<>(scopes.size());
        for (FontScope scope : scopes) {
            labels.add(switch (scope) {
                case LETTERING -> Component.translatableWithFallback(
                        "sandpaper.option.font_scope.lettering",
                        "settings and map lettering");
                case EVERYTHING -> Component.translatableWithFallback(
                        "sandpaper.option.font_scope.everything",
                        "every font in the game");
            });
        }
        return labels;
    }
    static void adoptFont(SandpaperConfig editing, AeroConfigScreen requestedBy,
                          String chosen) {
        if (requestedBy == null || requestedBy != currentConfigScreen()) {
            LOGGER.warn("a font pick was dropped; face {} installed but "
                    + "not selected.", chosen);
            reportFontPickDropped();
            return;
        }
        requestedBy.rebuildKeepingEdits(() -> editing.fontId = chosen,
                SandpaperConfigScreen::reportFontPickDropped);
    }
    private static AeroConfigScreen currentConfigScreen() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return null;
        }
        return client.gui.screen() instanceof AeroConfigScreen open ? open : null;
    }
    private static void reportFontPickDropped() {
        raiseToast(FONT_PICK_DROPPED_TOAST,
                Component.translatableWithFallback("sandpaper.toast.font_pick_dropped.title",
                        "Font not selected"),
                Component.translatableWithFallback("sandpaper.toast.font_pick_dropped.body",
                        "Font copied, not selected. Open settings to choose it"));
    }
    // Reads current at open and at Apply; touched tells the sink an edited row from an untouched one.
    private static OptionSpec shareRow(Component name, Component description, double shipped,
                                       DoubleSupplier current, DoubleConsumer sink) {
        boolean[] touched = {false};
        return new OptionSpec.IntSlider(name, description,
                sharePercent(shipped, MIN_SHARE_PERCENT, MAX_SHARE_PERCENT),
                sharePercent(current.getAsDouble(), MIN_SHARE_PERCENT, MAX_SHARE_PERCENT),
                percent -> sink.accept(shareToStore(percent, current.getAsDouble(),
                        shipped, touched[0], MIN_SHARE_PERCENT, MAX_SHARE_PERCENT)),
                MIN_SHARE_PERCENT, MAX_SHARE_PERCENT, 1,
                null,
                v -> touched[0] = true);
    }
    // Rounds to the nearest percent, not truncates; clamped to the slider's own bounds.
    static int sharePercent(double share, int minPercent, int maxPercent) {
        return Math.clamp(Math.round(share * 100.0), minPercent, maxPercent);
    }
    // Untouched returns current exactly; a touched value at the shipped percent returns shipped exactly.
    static double shareFromPercent(int percent, double current, double shipped, boolean touched,
                                   int minPercent, int maxPercent) {
        if (!touched) {
            return current;
        }
        return sharePercent(shipped, minPercent, maxPercent) == percent
                ? shipped
                : percent / 100.0;
    }
    static double shareToStore(int percent, double current, double shipped, boolean touched,
                               int minPercent, int maxPercent) {
        return shareFromPercent(percent, current, shipped, touched, minPercent, maxPercent);
    }
}
