package dev.sandpaper.client.gui.theme;

import dev.sandpaper.api.theme.ThemeSurface;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

public final class ThemeGameSurface implements ThemeSurface {

    private static final int NO_FONT_LINE_HEIGHT = 9;

    private final Map<Identifier, Boolean> availableTextures = new HashMap<>();
    private GuiGraphicsExtractor gfx = null;
    private Font font = null;
    private ResourceManager resources = null;
    private Minecraft client = null;
    private int clipDepth = 0;

    public void retarget(GuiGraphicsExtractor gfx, Font font) {
        this.gfx = gfx;
        this.font = font;
        clipDepth = 0;
        client = Minecraft.getInstance();
        ResourceManager currentResources = client == null ? null : client.getResourceManager();
        if (resources != currentResources) {
            availableTextures.clear();
            resources = currentResources;
        }
    }

    @Override
    public void fill(int x0, int y0, int x1, int y1, int argb) {
        if (gfx != null && x1 > x0 && y1 > y0) {
            gfx.fill(x0, y0, x1, y1, argb);
        }
    }

    @Override
    public void gradient(int x0, int y0, int x1, int y1, int topArgb, int bottomArgb) {
        if (gfx != null && x1 > x0 && y1 > y0) {
            gfx.fillGradient(x0, y0, x1, y1, topArgb, bottomArgb);
        }
    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        if (gfx != null && font != null && text != null && !text.isEmpty()) {
            gfx.text(font, text, x, y, argb, shadow);
        }
    }

    @Override
    public int textWidth(String text) {
        return font == null || text == null || text.isEmpty() ? 0 : font.width(text);
    }

    @Override
    public int lineHeight() {
        return font == null ? NO_FONT_LINE_HEIGHT : font.lineHeight;
    }

    @Override
    public boolean blitRegion(Identifier tex, int x, int y, int drawW, int drawH, float u, float v,
            int regionW, int regionH, int texW, int texH, int tintArgb) {
        if (gfx == null || tex == null || drawW <= 0 || drawH <= 0 || regionW <= 0 || regionH <= 0
                || texW <= 0 || texH <= 0 || !Float.isFinite(u) || !Float.isFinite(v)) {
            return false;
        }
        boolean drawn = false;
        try {
            if (textureAvailable(tex)) {
                gfx.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, u, v, drawW, drawH,
                        regionW, regionH, texW, texH, tintArgb);
                drawn = true;
            }
        } catch (RuntimeException refused) {
            drawn = false;
        }
        return drawn;
    }

    @Override
    public boolean blitRotated(Identifier tex, float centerX, float centerY, int drawW, int drawH,
            float radians, int texW, int texH, int tintArgb) {
        if (gfx == null || tex == null || drawW <= 0 || drawH <= 0 || texW <= 0 || texH <= 0
                || !Float.isFinite(centerX) || !Float.isFinite(centerY) || !Float.isFinite(radians)) {
            return false;
        }
        boolean drawn = false;
        try {
            if (textureAvailable(tex)) {
                var pose = gfx.pose();
                pose.pushMatrix();
                try {
                    pose.translate(centerX, centerY);
                    pose.rotate(radians);
                    gfx.blit(RenderPipelines.GUI_TEXTURED, tex, -drawW / 2, -drawH / 2, 0.0F, 0.0F,
                            drawW, drawH, texW, texH, texW, texH, tintArgb);
                } finally {
                    pose.popMatrix();
                }
                drawn = true;
            }
        } catch (RuntimeException refused) {
            drawn = false;
        }
        return drawn;
    }

    @Override
    public void clip(int x0, int y0, int x1, int y1) {
        if (gfx != null) {
            gfx.enableScissor(x0, y0, Math.max(x0, x1), Math.max(y0, y1));
            clipDepth++;
        }
    }

    @Override
    public void unclip() {
        if (gfx != null && clipDepth > 0) {
            gfx.disableScissor();
            clipDepth--;
        }
    }

    @Override
    public int width() {
        return gfx == null ? 0 : gfx.guiWidth();
    }

    @Override
    public int height() {
        return gfx == null ? 0 : gfx.guiHeight();
    }

    private boolean textureAvailable(Identifier texture) {
        Boolean cached = availableTextures.get(texture);
        boolean available;
        if (cached != null) {
            available = Boolean.TRUE.equals(cached);
        } else {
            available = resources != null && resources.getResource(texture).isPresent();
            availableTextures.put(texture, available ? Boolean.TRUE : Boolean.FALSE);
        }
        return available || (client != null
                && client.getTextureManager().getTexture(texture) instanceof DynamicTexture);
    }
}
