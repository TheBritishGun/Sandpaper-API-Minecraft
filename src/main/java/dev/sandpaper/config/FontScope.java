package dev.sandpaper.config;
// Different mechanisms, not strengths; both fall back to vanilla for missing glyphs.
public enum FontScope {
    LETTERING,
    EVERYTHING;
    // Whether the face replaces minecraft:font/default, rather than only this mod's text.
    public boolean replacesDefault() {
        return this == EVERYTHING;
    }
}
