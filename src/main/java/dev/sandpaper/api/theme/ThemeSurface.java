package dev.sandpaper.api.theme;

import net.minecraft.resources.Identifier;

public interface ThemeSurface {
    void fill(int x0, int y0, int x1, int y1, int argb);

    void gradient(int x0, int y0, int x1, int y1, int topArgb, int bottomArgb);

    void text(String text, int x, int y, int argb, boolean shadow);

    int textWidth(String text);

    int lineHeight();

    boolean blitRegion(Identifier tex, int x, int y, int drawW, int drawH, float u, float v,
            int regionW, int regionH, int texW, int texH, int tintArgb);

    boolean blitRotated(Identifier tex, float centerX, float centerY, int drawW, int drawH,
            float radians, int texW, int texH, int tintArgb);

    void clip(int x0, int y0, int x1, int y1);

    void unclip();

    int width();

    int height();
}
