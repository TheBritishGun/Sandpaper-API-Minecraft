package dev.sandpaper.api.theme;

import net.minecraft.resources.Identifier;

public record ThemeChrome(
        Identifier frameNormal,
        Identifier frameHover,
        int texWidth,
        int texHeight,
        int srcBorderPx,
        int minDrawBorderPx,
        int maxDrawBorderPx) {

    private static final int OPPOSITE_BORDERS = 2;

    public ThemeChrome {
        if (texWidth <= 0 || texHeight <= 0) {
            throw new IllegalArgumentException("Texture dimensions must be positive");
        }
        if (srcBorderPx <= 0 || srcBorderPx > Math.min(texWidth, texHeight) / OPPOSITE_BORDERS) {
            throw new IllegalArgumentException("Source borders must fit inside the texture");
        }
        if (minDrawBorderPx < 1 || maxDrawBorderPx < minDrawBorderPx) {
            throw new IllegalArgumentException("Draw border bounds are invalid");
        }
    }
}
