package dev.sandpaper.client.gui.theme;

public final class BlendedPalette {

    private static final int ALPHA_SHIFT = 24;
    private static final int RED_SHIFT = 16;
    private static final int GREEN_SHIFT = 8;
    private static final int CHANNEL_MASK = 0xFF;

    public int backdropTop = 0;
    public int backdropMid = 0;
    public int backdropBottom = 0;
    public int bannerTop = 0;
    public int bannerBottom = 0;
    public int railBright = 0;
    public int railShade = 0;
    public int cornerHighlight = 0;
    public int controlRest = 0;
    public int controlHover = 0;
    public int controlDisabled = 0;
    public int controlApplyRest = 0;
    public int controlApplyHover = 0;
    public int controlSheenHover = 0;
    public int controlSheenApply = 0;
    public int frameTintRest = 0;
    public int frameTintHover = 0;
    public int frameTintApply = 0;
    public int frameTintDisabled = 0;
    public int infoFill = 0;
    public int infoBorderOuter = 0;
    public int infoBorderInner = 0;
    public int sectionTitle = 0;
    public int sectionRule = 0;
    public int sectionRuleAccent = 0;
    public int textPrimary = 0;
    public int textSecondary = 0;
    public int textMuted = 0;
    public int textModified = 0;
    public int textInvalid = 0;
    public int accentPrimary = 0;
    public int accentBright = 0;

    public void copyFrom(BlendedPalette src) {
        backdropTop = src.backdropTop;
        backdropMid = src.backdropMid;
        backdropBottom = src.backdropBottom;
        bannerTop = src.bannerTop;
        bannerBottom = src.bannerBottom;
        railBright = src.railBright;
        railShade = src.railShade;
        cornerHighlight = src.cornerHighlight;
        controlRest = src.controlRest;
        controlHover = src.controlHover;
        controlDisabled = src.controlDisabled;
        controlApplyRest = src.controlApplyRest;
        controlApplyHover = src.controlApplyHover;
        controlSheenHover = src.controlSheenHover;
        controlSheenApply = src.controlSheenApply;
        frameTintRest = src.frameTintRest;
        frameTintHover = src.frameTintHover;
        frameTintApply = src.frameTintApply;
        frameTintDisabled = src.frameTintDisabled;
        infoFill = src.infoFill;
        infoBorderOuter = src.infoBorderOuter;
        infoBorderInner = src.infoBorderInner;
        sectionTitle = src.sectionTitle;
        sectionRule = src.sectionRule;
        sectionRuleAccent = src.sectionRuleAccent;
        textPrimary = src.textPrimary;
        textSecondary = src.textSecondary;
        textMuted = src.textMuted;
        textModified = src.textModified;
        textInvalid = src.textInvalid;
        accentPrimary = src.accentPrimary;
        accentBright = src.accentBright;
    }

    public void mixFrom(BlendedPalette a, BlendedPalette b, float weightB) {
        if (Float.isNaN(weightB) || weightB <= 0.0F) {
            copyFrom(a);
        } else if (weightB >= 1.0F) {
            copyFrom(b);
        } else {
            backdropTop = mix(a.backdropTop, b.backdropTop, weightB);
            backdropMid = mix(a.backdropMid, b.backdropMid, weightB);
            backdropBottom = mix(a.backdropBottom, b.backdropBottom, weightB);
            bannerTop = mix(a.bannerTop, b.bannerTop, weightB);
            bannerBottom = mix(a.bannerBottom, b.bannerBottom, weightB);
            railBright = mix(a.railBright, b.railBright, weightB);
            railShade = mix(a.railShade, b.railShade, weightB);
            cornerHighlight = mix(a.cornerHighlight, b.cornerHighlight, weightB);
            controlRest = mix(a.controlRest, b.controlRest, weightB);
            controlHover = mix(a.controlHover, b.controlHover, weightB);
            controlDisabled = mix(a.controlDisabled, b.controlDisabled, weightB);
            controlApplyRest = mix(a.controlApplyRest, b.controlApplyRest, weightB);
            controlApplyHover = mix(a.controlApplyHover, b.controlApplyHover, weightB);
            controlSheenHover = mix(a.controlSheenHover, b.controlSheenHover, weightB);
            controlSheenApply = mix(a.controlSheenApply, b.controlSheenApply, weightB);
            frameTintRest = mix(a.frameTintRest, b.frameTintRest, weightB);
            frameTintHover = mix(a.frameTintHover, b.frameTintHover, weightB);
            frameTintApply = mix(a.frameTintApply, b.frameTintApply, weightB);
            frameTintDisabled = mix(a.frameTintDisabled, b.frameTintDisabled, weightB);
            infoFill = mix(a.infoFill, b.infoFill, weightB);
            infoBorderOuter = mix(a.infoBorderOuter, b.infoBorderOuter, weightB);
            infoBorderInner = mix(a.infoBorderInner, b.infoBorderInner, weightB);
            sectionTitle = mix(a.sectionTitle, b.sectionTitle, weightB);
            sectionRule = mix(a.sectionRule, b.sectionRule, weightB);
            sectionRuleAccent = mix(a.sectionRuleAccent, b.sectionRuleAccent, weightB);
            textPrimary = mix(a.textPrimary, b.textPrimary, weightB);
            textSecondary = mix(a.textSecondary, b.textSecondary, weightB);
            textMuted = mix(a.textMuted, b.textMuted, weightB);
            textModified = mix(a.textModified, b.textModified, weightB);
            textInvalid = mix(a.textInvalid, b.textInvalid, weightB);
            accentPrimary = mix(a.accentPrimary, b.accentPrimary, weightB);
            accentBright = mix(a.accentBright, b.accentBright, weightB);
        }
    }

    private static int mix(int from, int to, float weight) {
        int alpha = mixChannel(from, to, ALPHA_SHIFT, weight);
        int red = mixChannel(from, to, RED_SHIFT, weight);
        int green = mixChannel(from, to, GREEN_SHIFT, weight);
        int blue = mixChannel(from, to, 0, weight);
        return (alpha << ALPHA_SHIFT) | (red << RED_SHIFT) | (green << GREEN_SHIFT) | blue;
    }

    private static int mixChannel(int from, int to, int shift, float weight) {
        int start = (from >>> shift) & CHANNEL_MASK;
        int end = (to >>> shift) & CHANNEL_MASK;
        return Math.round(start + (end - start) * weight);
    }
}
