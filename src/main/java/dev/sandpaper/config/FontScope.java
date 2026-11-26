package dev.sandpaper.config;
public enum FontScope {
    LETTERING,
    EVERYTHING;
    // Whether the face replaces the default font.
    public boolean replacesDefault() {
        return this == EVERYTHING;
    }
}
