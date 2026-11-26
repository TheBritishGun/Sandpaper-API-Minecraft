package dev.sandpaper.api.theme;

public record ThemePalette(
        int backdropTop,
        int backdropMid,
        int backdropBottom,
        int bannerTop,
        int bannerBottom,
        int railBright,
        int railShade,
        int cornerHighlight,
        int controlRest,
        int controlHover,
        int controlDisabled,
        int controlApplyRest,
        int controlApplyHover,
        int controlSheenHover,
        int controlSheenApply,
        int frameTintRest,
        int frameTintHover,
        int frameTintApply,
        int frameTintDisabled,
        int infoFill,
        int infoBorderOuter,
        int infoBorderInner,
        int sectionTitle,
        int sectionRule,
        int sectionRuleAccent,
        int textPrimary,
        int textSecondary,
        int textMuted,
        int textModified,
        int textInvalid,
        int accentPrimary,
        int accentBright) {

    private static final int TOKEN_COUNT = 32;

    // Copies ARGB tokens in component order.
    public void copyTo(int[] target) {
        if (target.length < TOKEN_COUNT) {
            throw new IllegalArgumentException("Palette needs space for 32 tokens");
        }
        int index = 0;
        target[index++] = backdropTop;
        target[index++] = backdropMid;
        target[index++] = backdropBottom;
        target[index++] = bannerTop;
        target[index++] = bannerBottom;
        target[index++] = railBright;
        target[index++] = railShade;
        target[index++] = cornerHighlight;
        target[index++] = controlRest;
        target[index++] = controlHover;
        target[index++] = controlDisabled;
        target[index++] = controlApplyRest;
        target[index++] = controlApplyHover;
        target[index++] = controlSheenHover;
        target[index++] = controlSheenApply;
        target[index++] = frameTintRest;
        target[index++] = frameTintHover;
        target[index++] = frameTintApply;
        target[index++] = frameTintDisabled;
        target[index++] = infoFill;
        target[index++] = infoBorderOuter;
        target[index++] = infoBorderInner;
        target[index++] = sectionTitle;
        target[index++] = sectionRule;
        target[index++] = sectionRuleAccent;
        target[index++] = textPrimary;
        target[index++] = textSecondary;
        target[index++] = textMuted;
        target[index++] = textModified;
        target[index++] = textInvalid;
        target[index++] = accentPrimary;
        target[index] = accentBright;
    }
}
