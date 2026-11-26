package dev.sandpaper.client.gui.theme;

import dev.sandpaper.api.theme.MenuTheme;
import dev.sandpaper.api.theme.ThemeBackdrop;
import dev.sandpaper.api.theme.ThemeBanner;
import dev.sandpaper.api.theme.ThemeCenterpiece;
import dev.sandpaper.api.theme.ThemeChrome;
import dev.sandpaper.api.theme.ThemeSurface;
import net.minecraft.resources.Identifier;

public final class ThemeFrameRenderer {

    private static final int BORDER_DIVISOR = 3;
    private static final int SLICES_PER_AXIS = 3;
    private static final int OPPOSITE_EDGES = 2;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int ALPHA_SHIFT = 24;
    private static final int RGB_MASK = 0x00FFFFFF;
    private static final float MIN_LAYER_ALPHA = 0.01F;
    private static final float DEFAULT_CORNER_SPAN = 0.86F;
    private static final float DEFAULT_RAIL_INSET_X = 0.095F;
    private static final float DEFAULT_RAIL_INSET_Y = 0.03F;
    private static final int MIN_BANNER_RAIL_DIMENSION = 6;
    private static final int SECTION_TEXT_GAP = 8;
    private static final int SECTION_RULE_TIP = 3;

    private ThemeFrameRenderer() {
    }

    public static void drawNineSlice(ThemeSurface surface, ThemeChrome chrome, int x, int y, int width, int height,
            boolean hovered, boolean disabled, boolean applyAccent, BlendedPalette palette) {
        if (width <= 0 || height <= 0) {
            return;
        }
        boolean hot = hovered && !disabled;
        boolean accent = applyAccent && !disabled;
        int fill = controlFill(palette, hot, disabled, accent);
        int tint = frameTint(palette, hot, disabled, accent);
        surface.fill(x, y, x + width, y + height, fill);
        if ((hot || accent) && width > 6 && height > 4) {
            surface.fill(x + 3, y + 2, x + width - 3, y + 3,
                    accent ? palette.controlSheenApply : palette.controlSheenHover);
        }
        boolean framed = false;
        if (chrome != null) {
            Identifier texture = hot || accent ? chrome.frameHover() : chrome.frameNormal();
            framed = drawSlices(surface, chrome, texture, x, y, width, height, tint);
        }
        if (!framed) {
            bevel(surface, x, y, width, height, tint, palette.railShade);
        }
    }

    public static void drawBanner(ThemeSurface surface, ThemeBanner banner, BlendedPalette palette, int x, int y,
            int width, int height, String title, boolean highlightTopRight, boolean peekActive) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int corner = Math.max(1, Math.min(width, height) / OPPOSITE_EDGES);
        boolean highlight = highlightTopRight || peekActive;
        drawBannerWellAndRails(surface, palette, x, y, width, height, corner,
                railInsetX(banner), railInsetY(banner), cornerSpan(banner));
        if (hasBannerRails(width, height)) {
            drawCorners(surface, banner, palette, x, y, width, height, corner, highlight);
        }
        int reservedRight = reservesRightSlot(banner) ? maxRightSlot(width, height) : 0;
        int boxWidth = Math.max(1, width - reservedRight);
        drawBannerTitle(surface, banner, palette, x, y, boxWidth, height, title);
        if (highlight) {
            drawPeekMark(surface, palette, x + width, y, corner);
        }
    }

    public static void drawBackdrop(ThemeSurface surface, ThemeRegistry registry, MenuTheme theme,
            ThemeBackdrop.BackdropContext ctx, BlendedPalette palette) {
        drawSky(surface, ctx, palette);
        drawBackdropLayer(surface, registry, theme, ctx);
    }

    public static void drawCenterpiece(ThemeSurface surface, ThemeRegistry registry, MenuTheme theme,
            ThemeCenterpiece.CenterpieceContext ctx) {
        if (theme == null || ctx.blendAlpha() < MIN_LAYER_ALPHA) {
            return;
        }
        switch (theme.centerpiece()) {
            case ThemeCenterpiece.None none -> {
            }
            case ThemeCenterpiece.Sprite sprite -> drawSprite(surface, sprite, ctx);
            case ThemeCenterpiece.Custom custom -> registry.paintCenterpiece(theme, surface, ctx);
        }
    }

    public static void drawSectionDivider(ThemeSurface surface, String title, int x, int y,
            int width, BlendedPalette palette) {
        if (width <= 0) {
            return;
        }
        int titleWidth = surface.textWidth(title);
        int textX = x + (width - titleWidth) / OPPOSITE_EDGES;
        int ruleY = y + surface.lineHeight() / OPPOSITE_EDGES;
        int leftEnd = textX - SECTION_TEXT_GAP;
        int rightStart = textX + titleWidth + SECTION_TEXT_GAP;
        if (leftEnd >= x + SECTION_RULE_TIP) {
            surface.fill(x, ruleY, leftEnd, ruleY + 1, palette.sectionRule);
            surface.fill(leftEnd - SECTION_RULE_TIP, ruleY, leftEnd, ruleY + 1, palette.sectionRuleAccent);
        }
        if (rightStart <= x + width - SECTION_RULE_TIP) {
            surface.fill(rightStart, ruleY, x + width, ruleY + 1, palette.sectionRule);
            surface.fill(rightStart, ruleY, rightStart + SECTION_RULE_TIP, ruleY + 1, palette.sectionRuleAccent);
        }
        surface.clip(x, y, x + width, y + surface.lineHeight());
        try {
            surface.text(title, textX, y, palette.sectionTitle, true);
        } finally {
            surface.unclip();
        }
    }

    public static void drawInfoPanel(ThemeSurface surface, int x, int y, int width, int height,
            BlendedPalette palette) {
        if (width <= 0 || height <= 0) {
            return;
        }
        surface.fill(x, y, x + width, y + height, palette.infoFill);
        bevel(surface, x, y, width, height, palette.infoBorderOuter, palette.infoBorderOuter);
        if (width > 4 && height > 3) {
            surface.fill(x + 2, y + 1, x + width - 2, y + 2, palette.infoBorderInner);
        }
    }

    static void drawBackdropLayer(ThemeSurface surface, ThemeRegistry registry, MenuTheme theme,
            ThemeBackdrop.BackdropContext ctx) {
        if (theme == null || ctx.blendAlpha() < MIN_LAYER_ALPHA) {
            return;
        }
        switch (theme.backdrop()) {
            case ThemeBackdrop.GradientOnly gradient -> {
            }
            case ThemeBackdrop.StaticTexture texture -> drawStaticBackdrop(surface, texture, ctx);
            case ThemeBackdrop.Custom custom -> registry.paintBackdrop(theme, surface, ctx);
        }
    }

    static void drawBannerBlend(ThemeSurface surface, ThemeBanner first, ThemeBanner second, float weight,
            BlendedPalette palette, int x, int y, int width, int height, String title,
            boolean highlight, boolean peek) {
        if (width <= 0 || height <= 0) {
            return;
        }
        float blend = Float.isNaN(weight) ? 0.0F : Math.clamp(weight, 0.0F, 1.0F);
        if (first == second || blend == 0.0F || blend == 1.0F) {
            drawBanner(surface, blend == 1.0F ? second : first, palette, x, y, width, height, title, highlight, peek);
            return;
        }
        float insetX = lerp(railInsetX(first), railInsetX(second), blend);
        float insetY = lerp(railInsetY(first), railInsetY(second), blend);
        float span = lerp(cornerSpan(first), cornerSpan(second), blend);
        int size = Math.max(1, Math.min(width, height) / OPPOSITE_EDGES);
        drawBannerWellAndRails(surface, palette, x, y, width, height, size, insetX, insetY, span);
        if (hasBannerRails(width, height)) {
            drawBlendedCorners(surface, first, second, palette, x, y, width, height, size,
                    highlight || peek, blend, insetX, insetY, span);
        }
        float reserveWeight = (reservesRightSlot(first) ? 1.0F - blend : 0.0F)
                + (reservesRightSlot(second) ? blend : 0.0F);
        int reservedRight = Math.round(maxRightSlot(width, height) * reserveWeight);
        int boxWidth = Math.max(1, width - reservedRight);
        drawBlendedBannerTitle(surface, first, second, palette, x, y, boxWidth, height, title, blend);
        if (highlight || peek) {
            drawPeekMark(surface, palette, x + width, y, size);
        }
    }

    private static boolean hasBannerRails(int width, int height) {
        return width >= MIN_BANNER_RAIL_DIMENSION && height >= MIN_BANNER_RAIL_DIMENSION;
    }

    private static boolean reservesRightSlot(ThemeBanner banner) {
        return banner != null && banner.reserveRightMascotSlot();
    }

    private static int maxRightSlot(int width, int height) {
        return Math.min(height, width / BORDER_DIVISOR);
    }

    private static float lerp(float start, float end, float weight) {
        return start + (end - start) * weight;
    }

    private static void drawPeekMark(ThemeSurface surface, BlendedPalette palette, int right, int y, int size) {
        int mark = Math.max(1, size / BORDER_DIVISOR);
        surface.fill(right - mark - 1, y + 1, right - 1, y + 2, palette.cornerHighlight);
    }

    private static int controlFill(BlendedPalette palette, boolean hovered, boolean disabled, boolean accent) {
        int fill;
        if (disabled) {
            fill = palette.controlDisabled;
        } else if (accent) {
            fill = hovered ? palette.controlApplyHover : palette.controlApplyRest;
        } else {
            fill = hovered ? palette.controlHover : palette.controlRest;
        }
        return fill;
    }

    private static int frameTint(BlendedPalette palette, boolean hovered, boolean disabled, boolean accent) {
        int tint;
        if (disabled) {
            tint = palette.frameTintDisabled;
        } else if (accent) {
            tint = palette.frameTintApply;
        } else {
            tint = hovered ? palette.frameTintHover : palette.frameTintRest;
        }
        return tint;
    }

    private static boolean drawSlices(ThemeSurface surface, ThemeChrome chrome, Identifier texture, int x, int y,
            int width, int height, int tint) {
        int limit = Math.min(width, height) / BORDER_DIVISOR;
        int source = chrome.srcBorderPx();
        if (texture == null || limit == 0
                || source * OPPOSITE_EDGES >= Math.min(chrome.texWidth(), chrome.texHeight())) {
            return false;
        }
        int border = Math.min(limit, Math.clamp(limit, chrome.minDrawBorderPx(), chrome.maxDrawBorderPx()));
        boolean drawn = true;
        for (int row = 0; row < SLICES_PER_AXIS && drawn; row++) {
            int dy = sliceStart(row, y, height, border);
            int sy = sliceStart(row, 0, chrome.texHeight(), source);
            int dh = sliceSize(row, height, border);
            int sh = sliceSize(row, chrome.texHeight(), source);
            for (int col = 0; col < SLICES_PER_AXIS && drawn; col++) {
                int dx = sliceStart(col, x, width, border);
                int sx = sliceStart(col, 0, chrome.texWidth(), source);
                int dw = sliceSize(col, width, border);
                int sw = sliceSize(col, chrome.texWidth(), source);
                drawn = surface.blitRegion(texture, dx, dy, dw, dh, sx, sy, sw, sh,
                        chrome.texWidth(), chrome.texHeight(), tint);
            }
        }
        return drawn;
    }

    private static int sliceStart(int index, int origin, int size, int border) {
        int start;
        if (index == 0) {
            start = origin;
        } else if (index == 1) {
            start = origin + border;
        } else {
            start = origin + size - border;
        }
        return start;
    }

    private static int sliceSize(int index, int size, int border) {
        return index == 1 ? size - OPPOSITE_EDGES * border : border;
    }

    private static void bevel(ThemeSurface surface, int x, int y, int width, int height, int bright, int shade) {
        int right = x + width;
        int bottom = y + height;
        surface.fill(x, y, right, y + 1, bright);
        surface.fill(x, y + 1, x + 1, bottom, bright);
        if (width > 1 && height > 1) {
            surface.fill(x + 1, bottom - 1, right, bottom, shade);
            surface.fill(right - 1, y + 1, right, bottom - 1, shade);
        }
    }

    private static float railInsetX(ThemeBanner banner) {
        return banner == null ? DEFAULT_RAIL_INSET_X : banner.railInsetXRatio();
    }

    private static float railInsetY(ThemeBanner banner) {
        return banner == null ? DEFAULT_RAIL_INSET_Y : banner.railInsetYRatio();
    }

    private static float cornerSpan(ThemeBanner banner) {
        return banner == null ? DEFAULT_CORNER_SPAN : banner.cornerSpanRatio();
    }

    private static void drawBannerWellAndRails(ThemeSurface surface, BlendedPalette palette, int x, int y,
            int width, int height, int corner, float insetX, float insetY, float span) {
        if (!hasBannerRails(width, height)) {
            surface.gradient(x, y, x + width, y + height, palette.bannerTop, palette.bannerBottom);
            return;
        }
        int left = x + Math.max(1, Math.round(corner * insetX));
        int right = x + width - Math.max(2, Math.round(corner * insetX)) - 1;
        int top = y + Math.max(1, Math.round(corner * insetY));
        int bottom = y + height - Math.max(2, Math.round(corner * insetY)) - 1;
        int wellLeft = left + 1;
        int wellRight = right + 1;
        int wellTop = top + 1;
        int wellBottom = bottom + 1;
        if (wellRight > wellLeft && wellBottom > wellTop) {
            surface.gradient(wellLeft, wellTop, wellRight, wellBottom, palette.bannerTop, palette.bannerBottom);
        } else {
            surface.gradient(x, y, x + width, y + height, palette.bannerTop, palette.bannerBottom);
        }
        drawRails(surface, palette, x, y, width, height, left, top, right, bottom, corner, span);
    }

    private static void drawRails(ThemeSurface surface, BlendedPalette palette, int x, int y,
            int width, int height, int left, int top, int right, int bottom, int corner, float span) {
        int tip = Math.max(1, Math.round(corner * span));
        int railLeft = x + tip;
        int railRight = x + width - tip;
        int railTop = y + tip;
        int railBottom = y + height - tip;
        if (railRight > railLeft) {
            surface.fill(railLeft, top, railRight, top + 1, palette.railBright);
            surface.fill(railLeft, top + 1, railRight, top + 2, palette.railShade);
            surface.fill(railLeft, bottom, railRight, bottom + 1, palette.railShade);
            surface.fill(railLeft, bottom + 1, railRight, bottom + 2, palette.railBright);
        }
        if (railBottom > railTop) {
            surface.fill(left, railTop, left + 1, railBottom, palette.railBright);
            surface.fill(left + 1, railTop, left + 2, railBottom, palette.railShade);
            surface.fill(right, railTop, right + 1, railBottom, palette.railShade);
            surface.fill(right + 1, railTop, right + 2, railBottom, palette.railBright);
        }
    }

    private static void drawCorners(ThemeSurface surface, ThemeBanner banner, BlendedPalette palette, int x, int y,
            int width, int height, int corner, boolean highlight) {
        int right = x + width - corner;
        int bottom = y + height - corner;
        drawCorner(surface, banner, palette, x, y, corner, false, false, false);
        drawCorner(surface, banner, palette, right, y, corner, true, false, highlight);
        drawCorner(surface, banner, palette, x, bottom, corner, false, true, false);
        drawCorner(surface, banner, palette, right, bottom, corner, true, true, false);
    }

    private static void drawBlendedCorners(ThemeSurface surface, ThemeBanner first, ThemeBanner second,
            BlendedPalette palette, int x, int y, int width, int height, int size, boolean highlight,
            float weight, float insetX, float insetY, float span) {
        for (int index = 0; index < OPPOSITE_EDGES * OPPOSITE_EDGES; index++) {
            boolean right = index % OPPOSITE_EDGES != 0;
            boolean bottom = index >= OPPOSITE_EDGES;
            int cornerX = right ? x + width - size : x;
            int cornerY = bottom ? y + height - size : y;
            boolean hot = right && !bottom && highlight;
            int tint = hot ? palette.cornerHighlight : WHITE;
            boolean firstDrawn = drawCornerAsset(surface, first, cornerX, cornerY, size, right, bottom,
                    tintWithAlpha(tint, 1.0F - weight));
            boolean secondDrawn = drawCornerAsset(surface, second, cornerX, cornerY, size, right, bottom,
                    tintWithAlpha(tint, weight));
            if (firstDrawn || secondDrawn) {
                if (!firstDrawn) {
                    drawVectorCorner(surface, first, palette,
                            cornerX, cornerY, size, right, bottom, hot, 1.0F - weight);
                }
                if (!secondDrawn) {
                    drawVectorCorner(surface, second, palette, cornerX, cornerY, size, right, bottom, hot, weight);
                }
            } else {
                drawVectorCorner(surface, insetX, insetY, span, palette,
                        cornerX, cornerY, size, right, bottom, hot, 1.0F);
            }
        }
    }

    private static void drawCorner(ThemeSurface surface, ThemeBanner banner, BlendedPalette palette, int x, int y,
            int size, boolean right, boolean bottom, boolean highlight) {
        int tint = highlight ? palette.cornerHighlight : WHITE;
        if (!drawCornerAsset(surface, banner, x, y, size, right, bottom, tint)) {
            drawVectorCorner(surface, banner, palette, x, y, size, right, bottom, highlight, 1.0F);
        }
    }

    private static boolean drawCornerAsset(ThemeSurface surface, ThemeBanner banner, int x, int y, int size,
            boolean right, boolean bottom, int tint) {
        if (banner == null || banner.ornateCorners() == null) {
            return false;
        }
        int tile = banner.cornerTileSize();
        int atlas = banner.cornerAtlasSize();
        return surface.blitRegion(banner.ornateCorners(), x, y, size, size, right ? tile : 0,
                bottom ? tile : 0, tile, tile, atlas, atlas, tint);
    }

    private static void drawVectorCorner(ThemeSurface surface, ThemeBanner banner, BlendedPalette palette,
            int x, int y, int size, boolean right, boolean bottom, boolean highlight, float alpha) {
        drawVectorCorner(surface, railInsetX(banner), railInsetY(banner), cornerSpan(banner), palette,
                x, y, size, right, bottom, highlight, alpha);
    }

    private static void drawVectorCorner(ThemeSurface surface, float insetX, float insetY, float span,
            BlendedPalette palette, int x, int y, int size, boolean right, boolean bottom, boolean highlight,
            float alpha) {
        if (alpha < MIN_LAYER_ALPHA) {
            return;
        }
        int bright = tintWithAlpha(highlight ? palette.cornerHighlight : palette.railBright, alpha);
        int shade = tintWithAlpha(palette.railShade, alpha);
        int length = Math.max(1, Math.round(size * span));
        int xOffset = Math.max(1, Math.round(size * insetX));
        int yOffset = Math.max(1, Math.round(size * insetY));
        int sx = right ? x + size - Math.max(2, xOffset) - 1 : x + xOffset;
        int sy = bottom ? y + size - Math.max(2, yOffset) - 1 : y + yOffset;
        int startX = right ? x + size - length : sx;
        int endX = right ? sx + 2 : x + length;
        int startY = bottom ? y + size - length : sy;
        int endY = bottom ? sy + 2 : y + length;
        surface.fill(startX, sy, endX, sy + 1, bottom ? shade : bright);
        surface.fill(startX, sy + 1, endX, sy + 2, bottom ? bright : shade);
        surface.fill(sx, startY, sx + 1, endY, right ? shade : bright);
        surface.fill(sx + 1, startY, sx + 2, endY, right ? bright : shade);
    }

    private static void drawBannerTitle(ThemeSurface surface, ThemeBanner banner, BlendedPalette palette, int x,
            int y, int boxWidth, int height, String title) {
        int center = x + boxWidth / OPPOSITE_EDGES;
        int availableHeight = Math.max(1, height - 4);
        boolean hasSubtitle = banner != null && banner.subtitleTex() != null;
        int wordHeight = hasSubtitle ? Math.max(1, availableHeight / OPPOSITE_EDGES) : availableHeight;
        boolean wordmark = banner != null && drawBannerImage(surface, banner.wordmarkTex(), banner.wordmarkW(),
                banner.wordmarkH(), center, y + 2, boxWidth, wordHeight);
        if (!wordmark) {
            int textHeight = hasSubtitle ? availableHeight : surface.lineHeight();
            int titleY = y + (height - textHeight) / OPPOSITE_EDGES;
            drawBannerText(surface, title, x, y, boxWidth, height, center, titleY, palette.textPrimary);
        }
        if (hasSubtitle) {
            drawBannerImage(surface, banner.subtitleTex(), banner.subtitleW(), banner.subtitleH(),
                    center, y + 2 + wordHeight, boxWidth, Math.max(1, availableHeight - wordHeight));
        }
    }

    private static void drawBlendedBannerTitle(ThemeSurface surface, ThemeBanner first, ThemeBanner second,
            BlendedPalette palette, int x, int y, int boxWidth, int height, String title, float weight) {
        int center = x + boxWidth / OPPOSITE_EDGES;
        int titleHeight = Math.max(1, height - 4);
        boolean firstMark = drawBannerMarks(surface, first, center, y + 2, boxWidth, titleHeight, 1.0F - weight);
        boolean secondMark = drawBannerMarks(surface, second, center, y + 2, boxWidth, titleHeight, weight);
        float textAlpha = (firstMark ? 0.0F : 1.0F - weight) + (secondMark ? 0.0F : weight);
        if (textAlpha >= MIN_LAYER_ALPHA) {
            int titleY = y + (height - surface.lineHeight()) / OPPOSITE_EDGES;
            drawBannerText(surface, title, x, y, boxWidth, height, center, titleY,
                    tintWithAlpha(palette.textPrimary, textAlpha));
        }
    }

    private static void drawBannerText(ThemeSurface surface, String title, int x, int y, int boxWidth, int height,
            int center, int titleY, int tint) {
        surface.clip(x, y, x + boxWidth, y + height);
        try {
            surface.text(title, center - surface.textWidth(title) / OPPOSITE_EDGES, titleY, tint, true);
        } finally {
            surface.unclip();
        }
    }

    private static boolean drawBannerImage(ThemeSurface surface, Identifier texture, int texWidth, int texHeight,
            int center, int y, int width, int height) {
        return drawBannerImage(surface, texture, texWidth, texHeight, center, y, width, height, WHITE);
    }

    private static boolean drawBannerImage(ThemeSurface surface, Identifier texture, int texWidth, int texHeight,
            int center, int y, int width, int height, int tint) {
        if (texture == null || width <= 0 || height <= 0) {
            return false;
        }
        double scale = Math.min((double) width / texWidth, (double) height / texHeight);
        int drawWidth = Math.max(1, (int) Math.round(texWidth * scale));
        int drawHeight = Math.max(1, (int) Math.round(texHeight * scale));
        int imageX = center - drawWidth / OPPOSITE_EDGES;
        int imageY = y + (height - drawHeight) / OPPOSITE_EDGES;
        return surface.blitRegion(texture, imageX, imageY,
                drawWidth, drawHeight, 0.0F, 0.0F, texWidth, texHeight, texWidth, texHeight, tint);
    }

    private static boolean drawBannerMarks(ThemeSurface surface, ThemeBanner banner, int center, int y,
            int width, int height, float alpha) {
        if (banner == null) {
            return false;
        }
        int tint = whiteWithAlpha(alpha);
        boolean hasSubtitle = banner.subtitleTex() != null;
        int wordHeight = hasSubtitle ? Math.max(1, height / OPPOSITE_EDGES) : height;
        boolean drewWord = drawBannerImage(surface, banner.wordmarkTex(), banner.wordmarkW(), banner.wordmarkH(),
                center, y, width, wordHeight, tint);
        if (hasSubtitle) {
            drawBannerImage(surface, banner.subtitleTex(), banner.subtitleW(), banner.subtitleH(),
                    center, y + wordHeight, width, Math.max(1, height - wordHeight), tint);
        }
        return drewWord;
    }

    private static void drawSky(ThemeSurface surface, ThemeBackdrop.BackdropContext ctx, BlendedPalette palette) {
        int width = ctx.screenWidth();
        int height = ctx.screenHeight();
        if (width > 0 && height > 0) {
            int middle = height / OPPOSITE_EDGES;
            surface.gradient(0, 0, width, middle, palette.backdropTop, palette.backdropMid);
            surface.gradient(0, middle, width, height, palette.backdropMid, palette.backdropBottom);
        }
    }

    private static void drawStaticBackdrop(ThemeSurface surface, ThemeBackdrop.StaticTexture texture,
            ThemeBackdrop.BackdropContext ctx) {
        int width = ctx.screenWidth();
        int height = ctx.screenHeight();
        if (width <= 0 || height <= 0 || texture.maxAlpha() <= 0.0F) {
            return;
        }
        int tint = whiteWithAlpha(ctx.blendAlpha() * texture.maxAlpha());
        double shift = ctx.displayScrollPx() * texture.scrollParallax();
        switch (texture.fitMode()) {
            case COVER -> drawCover(surface, texture, width, height, shift, tint);
            case TILE -> drawTiles(surface, texture, width, height, shift, tint);
            case HORIZON_BAND -> drawHorizon(surface, texture, ctx, shift, tint);
        }
    }

    private static void drawCover(ThemeSurface surface, ThemeBackdrop.StaticTexture texture, int width, int height,
            double shift, int tint) {
        int texWidth = texture.texWidth();
        int texHeight = texture.texHeight();
        double scale = Math.max((double) width / texWidth, (double) height / texHeight);
        int regionWidth = Math.clamp(Math.round(width / scale), 1, texWidth);
        int regionHeight = Math.clamp(Math.round(height / scale), 1, texHeight);
        float u = (texWidth - regionWidth) / (float) OPPOSITE_EDGES;
        float v = (float) Math.clamp((texHeight - regionHeight) / (double) OPPOSITE_EDGES + shift,
                0.0, texHeight - regionHeight);
        surface.blitRegion(texture.texture(), 0, 0, width, height, u, v, regionWidth, regionHeight,
                texWidth, texHeight, tint);
    }

    private static void drawTiles(ThemeSurface surface, ThemeBackdrop.StaticTexture texture, int width, int height,
            double shift, int tint) {
        int texWidth = texture.texWidth();
        int texHeight = texture.texHeight();
        int firstY = -Math.floorMod(Math.round(shift), texHeight);
        boolean drawn = true;
        for (long row = firstY; row < height && drawn; row += texHeight) {
            int y = (int) Math.max(0L, row);
            int v = (int) (y - row);
            int drawHeight = Math.min(texHeight - v, height - y);
            for (long col = 0; col < width && drawn; col += texWidth) {
                int x = (int) col;
                int drawWidth = Math.min(texWidth, width - x);
                drawn = surface.blitRegion(texture.texture(), x, y, drawWidth, drawHeight, 0.0F, v,
                        drawWidth, drawHeight, texWidth, texHeight, tint);
            }
        }
    }

    private static void drawHorizon(ThemeSurface surface, ThemeBackdrop.StaticTexture texture,
            ThemeBackdrop.BackdropContext ctx, double shift, int tint) {
        int width = ctx.screenWidth();
        int height = ctx.screenHeight();
        int band = Math.clamp(Math.round((double) width * texture.texHeight() / texture.texWidth()), 1, height);
        double horizon = ctx.contentBottom() - band / (double) OPPOSITE_EDGES - shift;
        int y = Math.clamp(Math.round(horizon), 0, height - band);
        surface.blitRegion(texture.texture(), 0, y, width, band, 0.0F, 0.0F,
                texture.texWidth(), texture.texHeight(), texture.texWidth(), texture.texHeight(), tint);
    }

    private static void drawSprite(ThemeSurface surface, ThemeCenterpiece.Sprite sprite,
            ThemeCenterpiece.CenterpieceContext ctx) {
        int width = Math.max(1, Math.round(ctx.suggestedDiameter()));
        int height = Math.clamp(Math.round((double) width * sprite.texHeight() / sprite.texWidth()),
                1, Integer.MAX_VALUE);
        double spin = ctx.elapsedSeconds() * sprite.idleSpinRadPerSec()
                + ctx.spanLocalScrollPx() * sprite.scrollSpinRadPerPx();
        surface.blitRotated(sprite.texture(), ctx.anchorX(), ctx.anchorY(), width, height,
                (float) Math.IEEEremainder(spin, Math.TAU), sprite.texWidth(), sprite.texHeight(),
                whiteWithAlpha(ctx.blendAlpha()));
    }

    private static int whiteWithAlpha(float alpha) {
        return tintWithAlpha(WHITE, alpha);
    }

    private static int tintWithAlpha(int tint, float alpha) {
        return (Math.round((tint >>> ALPHA_SHIFT) * Math.clamp(alpha, 0.0F, 1.0F)) << ALPHA_SHIFT)
                | (tint & RGB_MASK);
    }
}
