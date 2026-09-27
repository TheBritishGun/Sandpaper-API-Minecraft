package dev.sandpaper.client.gui;
import dev.sandpaper.Sandpaper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// The only real drawing surface; the sole class allowed to advance a draw stratum
public final class AeroGameSurface implements AeroSurface.Backdrop {
    private static final Logger LOGGER = LoggerFactory.getLogger(Sandpaper.MOD_ID);
    // Matches the other lineHeight() branch
    private static final int NO_FONT_LINE_HEIGHT = 9;
    // Logs each sprite failure kind once per run, not every frame
    private static volatile boolean reportedUnparsedSprite;
    private static volatile boolean reportedRefusedSprite;
    private static final int SPRITE_NAME_CACHE_LIMIT = 64;
    private static <K, V> LinkedHashMap<K, V> boundedLru(int limit) {
        return new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > limit;
            }
        };
    }
    private GuiGraphicsExtractor gfx;
    private Font font;
    private boolean blurAllowed;
    private final LinkedHashMap<String, Identifier> spriteNames =
            boundedLru(SPRITE_NAME_CACHE_LIMIT);
    // Blur flag must match reality; a wrong value makes blursBehind lie
    AeroGameSurface(GuiGraphicsExtractor gfx, Font font, boolean blurAllowed) {
        this.gfx = gfx;
        this.font = font;
        this.blurAllowed = blurAllowed;
    }
    // Uses the game's own font; no lettering operator applied
    public static AeroGameSurface of(GuiGraphicsExtractor gfx, Minecraft client) {
        return of(gfx, client, UnaryOperator.identity());
    }
    // Font may be null; blur setting is read once here and held for the frame
    public static AeroGameSurface of(GuiGraphicsExtractor gfx, Minecraft client,
                                     UnaryOperator<Font> lettering) {
        return of(null, gfx, client, lettering);
    }
    static AeroGameSurface of(AeroGameSurface surface, GuiGraphicsExtractor gfx, Minecraft client,
                              UnaryOperator<Font> lettering) {
        return of(surface, gfx, client, lettering, false);
    }
    // alwaysBlur forces the blur regardless of the player's menu setting
    static AeroGameSurface of(AeroGameSurface surface, GuiGraphicsExtractor gfx, Minecraft client,
                              UnaryOperator<Font> lettering, boolean alwaysBlur) {
        Font supplied = client == null ? null : client.font;
        Font chosen = lettering.apply(supplied);
        // Evaluated even when alwaysBlur is true; do not let a short circuit skip it
        boolean menuBlur = client == null || client.options == null
                || client.options.getMenuBackgroundBlurriness() >= 1;
        boolean blur = menuBlur || alwaysBlur;
        if (surface == null) {
            return new AeroGameSurface(gfx, chosen, blur);
        }
        surface.retarget(gfx, chosen, blur);
        return surface;
    }
    void retarget(GuiGraphicsExtractor gfx, Font font, boolean blurAllowed) {
        this.gfx = gfx;
        this.font = font;
        this.blurAllowed = blurAllowed;
    }
    @Override
    public void fill(int x0, int y0, int x1, int y1, int argb) {
        if (x1 > x0 && y1 > y0) {
            gfx.fill(x0, y0, x1, y1, argb);
        }
    }
    // Uses the engine's native gradient fill; the interface default draws it row by row
    @Override
    public void gradient(int x0, int y0, int x1, int y1, int topArgb, int bottomArgb) {
        if (x1 > x0 && y1 > y0) {
            gfx.fillGradient(x0, y0, x1, y1, topArgb, bottomArgb);
        }
    }
    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        if (font != null && text != null && !text.isEmpty()) {
            gfx.text(font, text, x, y, argb, shadow);
        }
    }
    private static final int TEXT_WIDTH_CACHE_LIMIT = 256;
    private static final int TEXT_WIDTH_FONT_CACHE_LIMIT = 64;
    private static final LinkedHashMap<Font, LinkedHashMap<String, Integer>> TEXT_WIDTH_CACHE =
            boundedLru(TEXT_WIDTH_FONT_CACHE_LIMIT);
    // Returns 0 when there is no font to measure with
    @Override
    public int textWidth(String text) {
        if (font == null || text == null) {
            return 0;
        }
        LinkedHashMap<String, Integer> cached = TEXT_WIDTH_CACHE.get(font);
        if (cached == null) {
            cached = boundedLru(TEXT_WIDTH_CACHE_LIMIT);
            TEXT_WIDTH_CACHE.put(font, cached);
        }
        Integer width = cached.get(text);
        if (width == null) {
            width = font.width(text);
            cached.put(text, width);
        }
        return width;
    }
    static void forgetTextWidths() {
        TEXT_WIDTH_CACHE.clear();
    }
    Font font() {
        return font;
    }
    @Override
    public Object faceKey() {
        return font;
    }
    // The null check is the only real effect here
    @Override
    public int lineHeight() {
        return font == null ? NO_FONT_LINE_HEIGHT : font.lineHeight;
    }
    // Draws a tinted GUI sprite; any failure to resolve or draw returns false, never throws
    @Override
    public boolean trySprite(String id, int x, int y, int w, int h, int argb) {
        if (id == null || w <= 0 || h <= 0) {
            return false;
        }
        Identifier name = spriteNames.get(id);
        if (name == null && !spriteNames.containsKey(id)) {
            name = Identifier.tryParse(id);
            spriteNames.put(id, name);
        }
        if (name == null) {
            if (!reportedUnparsedSprite) {
                reportedUnparsedSprite = true;
                LOGGER.warn("sprite {} is not a valid namespace:path name; nothing drawn.", id);
            }
            return false;
        }
        try {
            gfx.blitSprite(RenderPipelines.GUI_TEXTURED, name, x, y, w, h, argb);
            return true;
        } catch (RuntimeException refused) {
            if (!reportedRefusedSprite) {
                reportedRefusedSprite = true;
                LOGGER.warn("the sprite atlas refused sprite {}; nothing drawn.", id, refused);
            }
            return false;
        }
    }
    @Override
    public void sprite(String id, int x, int y, int w, int h, int argb) {
        trySprite(id, x, y, w, h, argb);
    }
    // Width in GUI scale units, not framebuffer pixels; matches fill's coordinates
    @Override
    public int width() {
        return gfx.guiWidth();
    }
    @Override
    public int height() {
        return gfx.guiHeight();
    }
    @Override
    public void clip(int x0, int y0, int x1, int y1) {
        gfx.enableScissor(x0, y0, x1, y1);
    }
    @Override
    public void unclip() {
        gfx.disableScissor();
    }
    // Advances the stratum unconditionally; the blur is conditional on blurAllowed
    @Override
    public void beginBlurredStratum() {
        gfx.nextStratum();
        if (blurAllowed) {
            gfx.blurBeforeThisStratum();
        }
    }
    // Same field beginBlurredStratum reads; set once at construction, not live
    @Override
    public boolean blursBehind() {
        return blurAllowed;
    }
}
