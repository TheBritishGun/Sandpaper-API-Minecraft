package dev.sandpaper.client.gui;
public interface AeroSurface {
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
    void text(String text, int x, int y, int argb, boolean shadow);
    int textWidth(String text);
    int lineHeight();
    default Object faceKey() {
        return this;
    }
    void sprite(String id, int x, int y, int w, int h, int argb);
    default boolean trySprite(String id, int x, int y, int w, int h, int argb) {
        sprite(id, x, y, w, h, argb);
        return id != null;
    }
    int width();
    int height();
    void clip(int x0, int y0, int x1, int y1);
    void unclip();
    interface Backdrop extends AeroSurface {
        void beginBlurredStratum();
        boolean blursBehind();
    }
}
