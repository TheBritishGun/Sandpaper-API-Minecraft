package dev.sandpaper.client.gui.theme;

public interface ThemeHost {

    MenuScrollPhysics scrollPhysics();

    BlendedPalette activePalette();

    BlendedPalette pagePalette(int page);

    int engagedCount();

    boolean isPeekMode();

    void togglePeek();

    void playApply();

    void playCancel();

    void syncSpans(int[] sectionY, int[] sectionEnd, int count);

    void frameBounds(int width, int height, int top, int bottom);

    double frameSeconds(long nowNanos);

    void stepFrame(double dtSeconds, int viewHeight, double focusLeadPx, int mouseX, int mouseY,
            boolean pointerInWindow);

    boolean handleCenterpieceClick(double mouseX, double mouseY);

    void paintBackdropAndCenterpiece(int screenWidth, int screenHeight);

    void paintBanner(int x, int y, int width, int height, String title, int mouseX, int mouseY);

    void paintInfoPanel(int x, int y, int width, int height);

    void paintSectionDivider(String title, int x, int y, int width, BlendedPalette palette);

    void paintActiveControl(int x, int y, int width, int height, boolean hovered, boolean disabled,
            boolean applyAccent);

    void paintPageControl(int page, int x, int y, int width, int height, boolean hovered,
            boolean disabled, boolean applyAccent);

    void fill(int x0, int y0, int x1, int y1, int argb);

    void text(String text, int x, int y, int argb, boolean shadow);

    int textWidth(String text);

    int lineHeight();

    void clip(int x0, int y0, int x1, int y1);

    void unclip();
}
