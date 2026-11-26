package dev.sandpaper.api.theme;

import net.minecraft.resources.Identifier;

public record ThemeBanner(
        Identifier ornateCorners,
        int cornerAtlasSize,
        int cornerTileSize,
        float railInsetXRatio,
        float railInsetYRatio,
        float cornerSpanRatio,
        Identifier wordmarkTex,
        int wordmarkW,
        int wordmarkH,
        Identifier subtitleTex,
        int subtitleW,
        int subtitleH,
        boolean reserveRightMascotSlot) {

    private static final int TILES_PER_SIDE = 2;
    private static final float MAX_RAIL_INSET = 0.5f;

    public ThemeBanner {
        if (cornerAtlasSize <= 0 || cornerTileSize <= 0 || cornerTileSize > cornerAtlasSize / TILES_PER_SIDE) {
            throw new IllegalArgumentException("Corner tiles must fit inside the atlas");
        }
        checkRailInset(railInsetXRatio);
        checkRailInset(railInsetYRatio);
        if (!Float.isFinite(cornerSpanRatio) || cornerSpanRatio <= 0.0f || cornerSpanRatio > 1.0f) {
            throw new IllegalArgumentException("Corner span ratio must be in (0, 1]");
        }
        checkTextureSize(wordmarkTex, wordmarkW, wordmarkH);
        checkTextureSize(subtitleTex, subtitleW, subtitleH);
    }

    private static void checkRailInset(float ratio) {
        if (!Float.isFinite(ratio) || ratio < 0.0f || ratio > MAX_RAIL_INSET) {
            throw new IllegalArgumentException("Rail inset ratio must be in [0, 0.5]");
        }
    }

    private static void checkTextureSize(Identifier texture, int width, int height) {
        if (width < 0 || height < 0 || (texture != null && (width == 0 || height == 0))) {
            throw new IllegalArgumentException("Banner texture dimensions are invalid");
        }
    }
}
