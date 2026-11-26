package dev.sandpaper.api.theme;

@FunctionalInterface
public interface ThemeAmbient extends AutoCloseable {
    void paint(ThemeSurface surface, ThemeBackdrop.BackdropContext ctx);

    @Override
    default void close() {
    }
}
