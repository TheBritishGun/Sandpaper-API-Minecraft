package dev.sandpaper.api.theme;

import net.minecraft.resources.Identifier;

public record ThemeSounds(
        Identifier hover,
        Identifier stepForward,
        Identifier stepBack,
        Identifier resetDefault,
        Identifier sliderSettle,
        Identifier sectionChange,
        Identifier apply,
        Identifier cancel,
        Identifier specialToggle) {

    public static final ThemeSounds DEFAULT = new ThemeSounds(null, null, null, null, null, null, null, null, null);
}
