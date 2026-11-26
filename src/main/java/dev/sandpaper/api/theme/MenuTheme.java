package dev.sandpaper.api.theme;

import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public record MenuTheme(
        Identifier id,
        Component displayName,
        ThemePalette palette,
        ThemeChrome chrome,
        ThemeBanner banner,
        ThemeBackdrop backdrop,
        ThemeCenterpiece centerpiece,
        ThemeAmbient ambient,
        ThemeSounds sounds) {

    public MenuTheme {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(palette, "palette");
        if (backdrop == null) {
            backdrop = new ThemeBackdrop.GradientOnly();
        }
        if (centerpiece == null) {
            centerpiece = new ThemeCenterpiece.None();
        }
        if (sounds == null) {
            sounds = ThemeSounds.DEFAULT;
        }
    }
}
