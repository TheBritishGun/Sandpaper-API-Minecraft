package dev.sandpaper.client.font;
import dev.sandpaper.Sandpaper;
import dev.sandpaper.config.FontScope;
import dev.sandpaper.config.SandpaperConfig;
import dev.sandpaper.font.FontLibrary;
import dev.sandpaper.font.FontLibrary.Face;
import dev.sandpaper.mixin.FontAccessor;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.repository.RepositorySource;
public final class SandpaperFonts {
    private static volatile FontLibrary library;
    private static volatile FontResourcePack.Selection fontSelection;
    private static volatile boolean packRegistered;
    private static Font.Provider builtOn;
    private static String builtFor;
    private static Font cached;
    private static final float DEFAULT_FONT_SIZE = 11f;
    private SandpaperFonts() {
    }
    public static Path fontDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve("sandpaper").resolve("fonts");
    }
    public static FontLibrary fonts() {
        FontLibrary present = library;
        if (present != null) {
            return present;
        }
        synchronized (SandpaperFonts.class) {
            present = library;
            if (present == null) {
                present = new FontLibrary(fontDirectory());
                present.refresh();
                library = present;
            }
        }
        return present;
    }
    public static boolean usableFace(String id) {
        Face face = fonts().face(id);
        return face != null && FontLibrary.knownTrueType(face.file());
    }
    public static RepositorySource packSource() {
        return packSource(fonts());
    }
    static RepositorySource packSource(FontLibrary library) {
        return FontResourcePack.source(library, SandpaperFonts::selection);
    }
    public static void packSourceRegistered() {
        packRegistered = true;
    }
    public static FontResourcePack.Selection selection() {
        FontResourcePack.Selection published = fontSelection;
        return published == null ? publishSelection() : published;
    }
    public static FontResourcePack.Selection publishSelection() {
        SandpaperConfig settings = Sandpaper.config();
        return publishSelection(settings, fonts().face(settings.fontId));
    }
    static FontResourcePack.Selection publishSelection(SandpaperConfig settings, Face face) {
        FontResourcePack.Selection taken = selectionFor(settings, face);
        fontSelection = taken;
        return taken;
    }
    static FontResourcePack.Selection selectionFor(SandpaperConfig settings, Face face) {
        float auto = face == null || face.autoSize() == null ? DEFAULT_FONT_SIZE : face.autoSize();
        float base = Float.isNaN(auto) ? DEFAULT_FONT_SIZE
                : Math.clamp(auto, FontLibrary.MIN_AUTO_SIZE, FontLibrary.MAX_AUTO_SIZE);
        int percent = SandpaperConfig.normaliseFontSizePercent(settings.fontSizePercent);
        float oversample = SandpaperConfig.normaliseFontOversample(settings.fontOversample);
        FontScope scope = SandpaperConfig.normaliseFontScope(settings.fontScope);
        return FontResourcePack.Selection.of(settings.fontId, scope.replacesDefault(),
                base * percent / 100f, oversample);
    }
    public static void settingsSaved() {
        SandpaperConfig settings = Sandpaper.config();
        settingsSaved(settings, fonts().face(settings.fontId),
                SandpaperFonts::reloadResourcePacks);
    }
    static boolean settingsSaved(SandpaperConfig settings, Face face, Runnable reload) {
        FontResourcePack.Selection before = fontSelection;
        FontResourcePack.Selection after = publishSelection(settings, face);
        invalidateLettering();
        if (!packRegistered || !servedDefinitionDiffers(before, after)) {
            return false;
        }
        reload.run();
        return true;
    }
    static boolean servedDefinitionDiffers(FontResourcePack.Selection before,
            FontResourcePack.Selection after) {
        if (before == null) {
            return true;
        }
        if (before.size() != after.size() || before.oversample() != after.oversample()) {
            return true;
        }
        if (before.everything() != after.everything()) {
            return true;
        }
        return after.everything() && !before.fontId().equals(after.fontId());
    }
    private static void reloadResourcePacks() {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.reloadResourcePacks();
        }
    }
    public static Font lettering(Font vanilla) {
        if (vanilla == null) {
            return null;
        }
        FontResourcePack.Selection selected = selection();
        if (keepsVanillaFont(selected)) {
            return vanilla;
        }
        return lettering(vanilla, fonts().face(selected.fontId()), selected);
    }
    static Font lettering(Font vanilla, Face face, FontResourcePack.Selection selected) {
        if (vanilla == null || face == null || keepsVanillaFont(selected)
                || !(vanilla instanceof FontAccessor accessor)) {
            return vanilla;
        }
        Font.Provider base = accessor.sandpaper$provider();
        if (base == null) {
            return vanilla;
        }
        Font built = cached;
        if (built != null && base == builtOn && selected.fontId().equals(builtFor)) {
            return built;
        }
        FontDescription description = new FontDescription.Resource(
                Identifier.fromNamespaceAndPath(FontResourcePack.NAMESPACE, selected.fontId()));
        built = new Font(new Font.Provider() {
            @Override
            public GlyphSource glyphs(FontDescription requested) {
                return base.glyphs(description);
            }
            @Override
            public EffectGlyph effect() {
                return base.effect();
            }
        });
        cached = built;
        builtOn = base;
        builtFor = selected.fontId();
        return built;
    }
    public static void invalidateLettering() {
        cached = null;
        builtOn = null;
        builtFor = null;
    }
    private static boolean keepsVanillaFont(FontResourcePack.Selection selected) {
        return selected.fontId().isEmpty() || selected.everything();
    }
}
