package dev.sandpaper.client.gui.theme;

import dev.sandpaper.api.theme.MenuTheme;
import dev.sandpaper.api.theme.ThemeBackdrop;
import dev.sandpaper.api.theme.ThemeCenterpiece;
import dev.sandpaper.api.theme.ThemeChrome;
import dev.sandpaper.api.theme.ThemePalette;
import dev.sandpaper.api.theme.ThemeSurface;
import dev.sandpaper.client.gui.AeroTheme;
import dev.sandpaper.client.gui.SettingsContributions;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ThemeMenuSession implements ThemeHost, AutoCloseable {

    private static final int FALLBACK_INDEX = 0;
    private static final int NO_SPAN = -1;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;
    private static final double MAX_FRAME_SECONDS = 0.1;
    private static final double DEFAULT_CROWN_RATIO = 0.58;
    private static final double DEFAULT_SCROLL_LIFT = 0.8;
    private static final double HERO_SIZE_RATIO = 0.34;
    private static final int MIN_HERO_DIAMETER = 24;
    private static final double HALF = 0.5;
    private static final float MIN_LAYER_ALPHA = 0.01F;
    private static final int BANNER_PEEK_ZONE = 22;

    private static final class MutableBackdropContext implements ThemeBackdrop.BackdropContext {
        private int screenWidth = 0;
        private int screenHeight = 0;
        private int contentTop = 0;
        private int contentBottom = 0;
        private double displayScrollPx = 0.0;
        private double sectionStartPx = 0.0;
        private double sectionEndPx = 0.0;
        private double sectionLocalProgress = 0.0;
        private float blendAlpha = 0.0F;
        private double elapsedSeconds = 0.0;
        private double dtSeconds = 0.0;
        private boolean peekActive = false;

        @Override
        public int screenWidth() {
            return screenWidth;
        }

        @Override
        public int screenHeight() {
            return screenHeight;
        }

        @Override
        public int contentTop() {
            return contentTop;
        }

        @Override
        public int contentBottom() {
            return contentBottom;
        }

        @Override
        public double displayScrollPx() {
            return displayScrollPx;
        }

        @Override
        public double sectionStartPx() {
            return sectionStartPx;
        }

        @Override
        public double sectionEndPx() {
            return sectionEndPx;
        }

        @Override
        public double sectionLocalProgress() {
            return sectionLocalProgress;
        }

        @Override
        public float blendAlpha() {
            return blendAlpha;
        }

        @Override
        public double elapsedSeconds() {
            return elapsedSeconds;
        }

        @Override
        public double dtSeconds() {
            return dtSeconds;
        }

        @Override
        public boolean peekActive() {
            return peekActive;
        }
    }

    private static final class MutableCenterpieceContext implements ThemeCenterpiece.CenterpieceContext {
        private float anchorX = 0.0F;
        private float anchorY = 0.0F;
        private float suggestedDiameter = 0.0F;
        private double spanLocalScrollPx = 0.0;
        private double spanHeightPx = 0.0;
        private double scrollPassProgress = 0.0;
        private float blendAlpha = 0.0F;
        private double mouseX = 0.0;
        private double mouseY = 0.0;
        private boolean pointerInWindow = false;
        private double elapsedSeconds = 0.0;
        private double dtSeconds = 0.0;

        @Override
        public float anchorX() {
            return anchorX;
        }

        @Override
        public float anchorY() {
            return anchorY;
        }

        @Override
        public float suggestedDiameter() {
            return suggestedDiameter;
        }

        @Override
        public double spanLocalScrollPx() {
            return spanLocalScrollPx;
        }

        @Override
        public double spanHeightPx() {
            return spanHeightPx;
        }

        @Override
        public double scrollPassProgress() {
            return scrollPassProgress;
        }

        @Override
        public float blendAlpha() {
            return blendAlpha;
        }

        @Override
        public double mouseX() {
            return mouseX;
        }

        @Override
        public double mouseY() {
            return mouseY;
        }

        @Override
        public boolean pointerInWindow() {
            return pointerInWindow;
        }

        @Override
        public double elapsedSeconds() {
            return elapsedSeconds;
        }

        @Override
        public double dtSeconds() {
            return dtSeconds;
        }
    }

    private final ThemeRegistry registry;
    private final MenuScrollPhysics scrollPhysics = new MenuScrollPhysics();
    private final ThemeBlender blender = new ThemeBlender();
    private final MenuTheme[] themes;
    private final BlendedPalette[] themePalettes;
    private final BlendedPalette activePalette = new BlendedPalette();
    private final MenuAudio audio;
    private final MutableBackdropContext backdrop = new MutableBackdropContext();
    private final MutableCenterpieceContext primaryHero = new MutableCenterpieceContext();
    private final MutableCenterpieceContext secondaryHero = new MutableCenterpieceContext();
    private final int[] pageThemes;
    private int frameWidth = 0;
    private int frameHeight = 0;
    private int contentTop = 0;
    private int contentBottom = 0;
    private boolean peekMode = false;
    private boolean closed = false;
    private double elapsedSeconds = 0.0;
    private long lastNanos = 0L;
    private boolean hasFrameSample = false;
    private AeroTheme.Palette fallbackColours = null;
    private ThemeSurface boundSurface = null;

    private ThemeMenuSession(ThemeRegistry registry, MenuTheme[] resolvedPerPage, MenuAudio audio) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.audio = Objects.requireNonNull(audio, "audio");
        pageThemes = new int[resolvedPerPage.length];
        MenuTheme fallback = registry.resolve(null, null);
        MenuTheme[] unique = new MenuTheme[resolvedPerPage.length + 1];
        Map<MenuTheme, Integer> indexes = new IdentityHashMap<>();
        unique[FALLBACK_INDEX] = fallback;
        indexes.put(fallback, Integer.valueOf(FALLBACK_INDEX));
        int count = 1;
        for (int page = 0; page < resolvedPerPage.length; page++) {
            MenuTheme theme = Objects.requireNonNullElse(resolvedPerPage[page], fallback);
            Integer index = indexes.get(theme);
            if (index == null) {
                unique[count] = theme;
                index = Integer.valueOf(count);
                indexes.put(theme, index);
                count++;
            }
            pageThemes[page] = index.intValue();
        }
        themes = Arrays.copyOf(unique, count);
        themePalettes = new BlendedPalette[count];
        for (int index = 0; index < count; index++) {
            BlendedPalette palette = new BlendedPalette();
            copyPaletteTo(themes[index].palette(), palette);
            themePalettes[index] = palette;
        }
        activePalette.copyFrom(themePalettes[FALLBACK_INDEX]);
    }

    public static ThemeMenuSession open(List<SettingsContributions.Contributed> pages, AeroTheme.Treatment treatment) {
        ThemeRegistry registry = ThemeRegistry.gather(ThemeWorkbenchSkeleton.fallbackTheme(treatment));
        boolean opened = false;
        try {
            MenuTheme[] resolved = new MenuTheme[pages.size()];
            for (int index = 0; index < resolved.length; index++) {
                SettingsContributions.Contributed page = pages.get(index);
                resolved[index] = registry.resolve(page.modId(), page.id());
            }
            ThemeMenuSession session = new ThemeMenuSession(registry, resolved, MenuAudio.create());
            session.fallbackColours = AeroTheme.colours(treatment);
            opened = true;
            return session;
        } finally {
            if (!opened) {
                registry.close();
            }
        }
    }

    public MenuScrollPhysics scrollPhysics() {
        return scrollPhysics;
    }

    public void refreshFallback(AeroTheme.Treatment treatment) {
        AeroTheme.Palette colours = AeroTheme.colours(treatment);
        if (fallbackColours != colours) {
            fallbackColours = colours;
            copyPaletteTo(ThemeWorkbenchSkeleton.fallbackPalette(colours), themePalettes[FALLBACK_INDEX]);
        }
    }

    public void playHover() {
        audio.playHover(dominantTheme().sounds());
    }

    public void playOptionStep(boolean forward) {
        audio.playOptionStep(forward, dominantTheme().sounds());
    }

    public void playOptionDefault() {
        audio.playOptionDefault(dominantTheme().sounds());
    }

    public void playSliderSettle() {
        audio.playSliderSettle(dominantTheme().sounds());
    }

    public void playPageJump(boolean forward) {
        audio.playPageJump(forward, dominantTheme().sounds());
    }

    public void playApply() {
        audio.playApply(dominantTheme().sounds());
    }

    public void playCancel() {
        audio.playCancel(dominantTheme().sounds());
    }

    public int engagedCount() {
        return registry.engagedCount();
    }

    public BlendedPalette activePalette() {
        return activePalette;
    }

    public BlendedPalette pagePalette(int page) {
        return themePalettes[pageThemes[page]];
    }

    public ThemeChrome pageChrome(int page) {
        return themes[pageThemes[page]].chrome();
    }

    public ThemeChrome activeChrome() {
        return dominantTheme().chrome();
    }

    public void syncSpans(int[] sectionY, int[] sectionEnd, int count) {
        blender.rebuildSpans(sectionY, sectionEnd, pageThemes, count);
    }

    public void frameBounds(int width, int height, int top, int bottom) {
        frameWidth = width;
        frameHeight = height;
        contentTop = top;
        contentBottom = bottom;
    }

    public double frameSeconds(long nowNanos) {
        double seconds = hasFrameSample ? (nowNanos - lastNanos) / NANOS_PER_SECOND : 0.0;
        lastNanos = nowNanos;
        hasFrameSample = true;
        return seconds;
    }

    public void stepFrame(double dtSeconds, int viewHeight, double focusLeadPx, int mouseX, int mouseY,
            boolean pointerInWindow) {
        if (closed) {
            return;
        }
        double dt = Double.isFinite(dtSeconds) ? Math.clamp(dtSeconds, 0.0, MAX_FRAME_SECONDS) : 0.0;
        elapsedSeconds += dt;
        scrollPhysics.step(dt);
        blender.update(scrollPhysics.displayScrollPx(), viewHeight, focusLeadPx, dt);
        blender.blendPaletteInto(themePalettes, activePalette);
        if (blender.consumeCrossing()) {
            audio.playSectionCrossing(dominantTheme().sounds());
        }
        backdrop.elapsedSeconds = elapsedSeconds;
        backdrop.dtSeconds = dt;
        backdrop.displayScrollPx = scrollPhysics.displayScrollPx();
        backdrop.peekActive = peekMode;
        int screenHeight = frameHeight > 0 ? frameHeight : viewHeight;
        refreshHero(primaryHero, blender.primarySpanIndex(), 1.0F - blender.blendWeightB(), screenHeight);
        refreshHero(secondaryHero, blender.secondarySpanIndex(), blender.blendWeightB(), screenHeight);
        updatePointer(primaryHero, mouseX, mouseY, pointerInWindow);
        updatePointer(secondaryHero, mouseX, mouseY, pointerInWindow);
    }

    public void paintBackdropAndCenterpiece(ThemeSurface surface, int screenWidth, int screenHeight) {
        if (closed) {
            return;
        }
        backdrop.screenWidth = screenWidth;
        backdrop.screenHeight = screenHeight;
        backdrop.contentTop = contentTop;
        backdrop.contentBottom = contentBottom;
        int first = blender.primarySpanIndex();
        int second = blender.secondarySpanIndex();
        float secondWeight = blender.blendWeightB();
        setBackdropBlend(first, 1.0F - secondWeight);
        ThemeFrameRenderer.drawBackdrop(surface, registry, themeForSpan(first), backdrop, activePalette);
        setBackdropBlend(second, secondWeight);
        ThemeFrameRenderer.drawBackdropLayer(surface, registry, themeForSpan(second), backdrop);
        paintHero(surface, first, primaryHero);
        paintHero(surface, second, secondaryHero);
        paintAmbient(surface, first, 1.0F - secondWeight);
        paintAmbient(surface, second, secondWeight);
    }

    public void paintBanner(ThemeSurface surface, int x, int y, int width, int height, String title,
            int mouseX, int mouseY) {
        boolean highlight = mouseX >= x + width - BANNER_PEEK_ZONE && mouseX < x + width
                && mouseY >= y && mouseY < y + height;
        ThemeFrameRenderer.drawBannerBlend(surface, themeForSpan(blender.primarySpanIndex()).banner(),
                themeForSpan(blender.secondarySpanIndex()).banner(), blender.blendWeightB(), activePalette,
                x, y, width, height, title, highlight, peekMode);
    }

    public boolean handleCenterpieceClick(double mouseX, double mouseY) {
        if (closed) {
            return false;
        }
        MenuTheme theme = dominantTheme();
        boolean primary = theme == themeForSpan(blender.primarySpanIndex());
        MutableCenterpieceContext hero = primary ? primaryHero : secondaryHero;
        return hero.blendAlpha >= MIN_LAYER_ALPHA && registry.pointerDownCenterpiece(theme, mouseX, mouseY, hero);
    }

    public void togglePeek() {
        if (!closed) {
            peekMode = !peekMode;
            audio.playCornerToggle(peekMode, dominantTheme().sounds());
        }
    }

    public boolean isPeekMode() {
        return peekMode;
    }

    public void bindSurface(ThemeSurface surface) {
        boundSurface = surface;
    }

    @Override
    public void paintBackdropAndCenterpiece(int screenWidth, int screenHeight) {
        if (boundSurface != null) {
            paintBackdropAndCenterpiece(boundSurface, screenWidth, screenHeight);
        }
    }

    @Override
    public void paintBanner(int x, int y, int width, int height, String title, int mouseX, int mouseY) {
        if (boundSurface != null) {
            paintBanner(boundSurface, x, y, width, height, title, mouseX, mouseY);
        }
    }

    @Override
    public void paintInfoPanel(int x, int y, int width, int height) {
        if (boundSurface != null) {
            ThemeFrameRenderer.drawInfoPanel(boundSurface, x, y, width, height, activePalette);
        }
    }

    @Override
    public void paintSectionDivider(String title, int x, int y, int width, BlendedPalette palette) {
        if (boundSurface != null) {
            ThemeFrameRenderer.drawSectionDivider(boundSurface, title, x, y, width, palette);
        }
    }

    @Override
    public void paintActiveControl(int x, int y, int width, int height, boolean hovered, boolean disabled,
            boolean applyAccent) {
        if (boundSurface != null) {
            ThemeFrameRenderer.drawNineSlice(boundSurface, activeChrome(), x, y, width, height,
                    hovered, disabled, applyAccent, activePalette);
        }
    }

    @Override
    public void paintPageControl(int page, int x, int y, int width, int height, boolean hovered,
            boolean disabled, boolean applyAccent) {
        if (boundSurface != null) {
            ThemeFrameRenderer.drawNineSlice(boundSurface, pageChrome(page), x, y, width, height,
                    hovered, disabled, applyAccent, pagePalette(page));
        }
    }

    @Override
    public void fill(int x0, int y0, int x1, int y1, int argb) {
        if (boundSurface != null) {
            boundSurface.fill(x0, y0, x1, y1, argb);
        }
    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        if (boundSurface != null) {
            boundSurface.text(text, x, y, argb, shadow);
        }
    }

    @Override
    public int textWidth(String text) {
        return boundSurface == null ? 0 : boundSurface.textWidth(text);
    }

    @Override
    public int lineHeight() {
        return boundSurface == null ? 9 : boundSurface.lineHeight();
    }

    @Override
    public void clip(int x0, int y0, int x1, int y1) {
        if (boundSurface != null) {
            boundSurface.clip(x0, y0, x1, y1);
        }
    }

    @Override
    public void unclip() {
        if (boundSurface != null) {
            boundSurface.unclip();
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            registry.close();
        }
    }

    static ThemeMenuSession create(ThemeRegistry registry, MenuTheme[] resolvedPerPage, MenuAudio audio) {
        return new ThemeMenuSession(registry, resolvedPerPage, audio);
    }

    static void copyPaletteTo(ThemePalette src, BlendedPalette dst) {
        dst.backdropTop = src.backdropTop();
        dst.backdropMid = src.backdropMid();
        dst.backdropBottom = src.backdropBottom();
        dst.bannerTop = src.bannerTop();
        dst.bannerBottom = src.bannerBottom();
        dst.railBright = src.railBright();
        dst.railShade = src.railShade();
        dst.cornerHighlight = src.cornerHighlight();
        dst.controlRest = src.controlRest();
        dst.controlHover = src.controlHover();
        dst.controlDisabled = src.controlDisabled();
        dst.controlApplyRest = src.controlApplyRest();
        dst.controlApplyHover = src.controlApplyHover();
        dst.controlSheenHover = src.controlSheenHover();
        dst.controlSheenApply = src.controlSheenApply();
        dst.frameTintRest = src.frameTintRest();
        dst.frameTintHover = src.frameTintHover();
        dst.frameTintApply = src.frameTintApply();
        dst.frameTintDisabled = src.frameTintDisabled();
        dst.infoFill = src.infoFill();
        dst.infoBorderOuter = src.infoBorderOuter();
        dst.infoBorderInner = src.infoBorderInner();
        dst.sectionTitle = src.sectionTitle();
        dst.sectionRule = src.sectionRule();
        dst.sectionRuleAccent = src.sectionRuleAccent();
        dst.textPrimary = src.textPrimary();
        dst.textSecondary = src.textSecondary();
        dst.textMuted = src.textMuted();
        dst.textModified = src.textModified();
        dst.textInvalid = src.textInvalid();
        dst.accentPrimary = src.accentPrimary();
        dst.accentBright = src.accentBright();
    }

    private void refreshHero(MutableCenterpieceContext hero, int span, float weight, int height) {
        if (span == NO_SPAN) {
            hero.blendAlpha = 0.0F;
            return;
        }
        double start = blender.spanStartPx(span);
        double spanHeight = blender.spanEndPx(span) - start;
        double crownRatio = DEFAULT_CROWN_RATIO;
        double lift = DEFAULT_SCROLL_LIFT;
        double size = Math.max(MIN_HERO_DIAMETER, height * HERO_SIZE_RATIO);
        MenuTheme theme = themeForSpan(span);
        if (theme.centerpiece() instanceof ThemeCenterpiece.Sprite sprite) {
            crownRatio = sprite.crownYRatio();
            lift = sprite.scrollLiftFactor();
            size *= sprite.widthScale();
        }
        double y = scrollPhysics.heroAnchorY(height * crownRatio, start, lift);
        double margin = size * HALF;
        hero.anchorX = (float) (frameWidth * HALF);
        hero.anchorY = (float) y;
        hero.suggestedDiameter = (float) Math.min(size, Integer.MAX_VALUE);
        hero.spanLocalScrollPx = scrollPhysics.displayScrollPx() - start;
        hero.spanHeightPx = spanHeight;
        hero.scrollPassProgress = spanHeight > 0.0 ? hero.spanLocalScrollPx / spanHeight : 0.0;
        hero.blendAlpha = weight * MenuScrollPhysics.heroEdgeFade(y, height, margin);
        hero.elapsedSeconds = elapsedSeconds;
        hero.dtSeconds = backdrop.dtSeconds;
    }

    private void paintHero(ThemeSurface surface, int span, MutableCenterpieceContext hero) {
        if (span != NO_SPAN && MenuScrollPhysics.isHeroVisible(hero.anchorY, backdrop.screenHeight,
                hero.suggestedDiameter * HALF)) {
            ThemeFrameRenderer.drawCenterpiece(surface, registry, themeForSpan(span), hero);
        }
    }

    private void paintAmbient(ThemeSurface surface, int span, float weight) {
        if (weight >= MIN_LAYER_ALPHA) {
            setBackdropBlend(span, weight);
            registry.paintAmbient(themeForSpan(span), surface, backdrop);
        }
    }

    private void setBackdropBlend(int span, float weight) {
        double start = span == NO_SPAN ? 0.0 : blender.spanStartPx(span);
        double end = span == NO_SPAN ? 0.0 : blender.spanEndPx(span);
        backdrop.sectionStartPx = start;
        backdrop.sectionEndPx = end;
        backdrop.sectionLocalProgress = end > start
                ? Math.clamp((backdrop.displayScrollPx - start) / (end - start), 0.0, 1.0) : 0.0;
        backdrop.blendAlpha = weight;
    }

    private MenuTheme themeForSpan(int span) {
        return span == NO_SPAN ? themes[FALLBACK_INDEX] : themes[blender.spanThemeIndex(span)];
    }

    private MenuTheme dominantTheme() {
        int index = blender.dominantThemeIndex();
        return themes[index == NO_SPAN ? FALLBACK_INDEX : index];
    }

    private static void updatePointer(MutableCenterpieceContext hero, int mouseX, int mouseY,
            boolean pointerInWindow) {
        hero.mouseX = mouseX;
        hero.mouseY = mouseY;
        hero.pointerInWindow = pointerInWindow;
    }
}
