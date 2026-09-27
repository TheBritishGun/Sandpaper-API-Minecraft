package dev.sandpaper.client.gui;
// What a page draws with; frame-level calls live on Backdrop, not here
public interface AeroSurface {
    // Half-open; x1 and y1 are not drawn
    void fill(int x0, int y0, int x1, int y1, int argb);
    default void gradient(int x0, int y0, int x1, int y1, int topArgb, int bottomArgb) {
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        int rows = y1 - y0;
        if (rows == 1 || topArgb == bottomArgb) {
            fill(x0, y0, x1, y1, topArgb);
            return;
        }
        for (int y = y0; y < y1; y++) {
            fill(x0, y, x1, y + 1,
                    AeroTheme.mix(topArgb, bottomArgb, (float) (y - y0) / (rows - 1)));
        }
    }
    // Top-left corner at x, y
    void text(String text, int x, int y, int argb, boolean shadow);
    int textWidth(String text);
    int lineHeight();
    default Object faceKey() {
        return this;
    }
    // 0xFFFFFFFF is as authored; must not throw if unresolved
    void sprite(String id, int x, int y, int w, int h, int argb);
    default boolean trySprite(String id, int x, int y, int w, int h, int argb) {
        sprite(id, x, y, w, h, argb);
        return id != null;
    }
    // Same coordinates fill takes
    int width();
    int height();
    // Until the matching unclip; nests
    void clip(int x0, int y0, int x1, int y1);
    void unclip();
    // Whole-screen; only the host implements it, a page never sees it
    interface Backdrop extends AeroSurface {
        // Advances the stratum unconditionally, blurring only if allowed; call once per frame, first
        void beginBlurredStratum();
        // Safe to call anytime; a stub with no blur returns false
        boolean blursBehind();
    }
}
