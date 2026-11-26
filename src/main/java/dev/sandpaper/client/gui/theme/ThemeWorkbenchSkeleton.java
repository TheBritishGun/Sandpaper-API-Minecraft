package dev.sandpaper.client.gui.theme;

import dev.sandpaper.api.theme.MenuTheme;
import dev.sandpaper.api.theme.ThemeBackdrop;
import dev.sandpaper.api.theme.ThemeCenterpiece;
import dev.sandpaper.api.theme.ThemeContributor;
import dev.sandpaper.api.theme.ThemePalette;
import dev.sandpaper.api.theme.ThemeSounds;
import dev.sandpaper.client.gui.AeroTheme;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public final class ThemeWorkbenchSkeleton implements ThemeContributor {

    public static final Identifier WORKBENCH_ID = Identifier.fromNamespaceAndPath("sandpaper", "workbench");
    public static final Identifier FALLBACK_ID = Identifier.fromNamespaceAndPath("sandpaper", "aero_fallback");

    private static final ThemePalette WORKBENCH = new ThemePalette(
            0xFF39271C, 0xFF271B15, 0xFF15100D,
            0xFF59402B, 0xFF2E2017,
            0xFFC99648, 0xFF71502D, 0xFFF0CE84,
            0xE0251A12, 0xEC493221, 0xCC211912, 0xE65B3D21, 0xF0714B26,
            0x62FFE6A8, 0xAAFFD783,
            0xFFC99648, 0xFFF0CE84, 0xFFFFDB8F, 0xB08A765D,
            0xEB20170F, 0xFFC99648, 0x68F0CE84,
            0xFFF0CE84, 0xA8C99648, 0xFFF0CE84,
            0xFFF5EDE0, 0xFFE0CEB7, 0xFFB2A28E, 0xFFFFD783, 0xFFFFA283,
            0xFFC99648, 0xFFF0CE84);

    public ThemeWorkbenchSkeleton() {
    }

    public static MenuTheme workbenchTheme() {
        return bareTheme(WORKBENCH_ID, Component.translatable("sandpaper.theme.workbench"), WORKBENCH);
    }

    public static MenuTheme fallbackTheme(AeroTheme.Treatment treatment) {
        AeroTheme.Palette colours = AeroTheme.colours(treatment);
        return bareTheme(FALLBACK_ID, Component.translatable("sandpaper.theme.aero_fallback"),
                fallbackPalette(colours));
    }

    static ThemePalette fallbackPalette(AeroTheme.Palette colours) {
        return new ThemePalette(
                colours.glassTop, AeroTheme.mix(colours.glassTop, colours.glassBottom, 0.5F), colours.glassBottom,
                colours.buttonTop, colours.buttonBottom,
                colours.edgeLight, colours.edgeDark, colours.accentLight,
                colours.wellTop, colours.rowHover, colours.wellBottom, colours.accentDeep, colours.accent,
                colours.specular, colours.glow,
                colours.edgeLight, colours.accentLight, colours.accent, colours.edgeDark,
                colours.glassBottom, colours.edgeLight, colours.rule,
                colours.textHead, colours.rule, colours.ruleStrong,
                colours.text, colours.textDim, colours.textOff, colours.valueChanged, colours.valueInvalid,
                colours.accent, colours.accentLight);
    }

    @Override
    public List<MenuTheme> themes() {
        return List.of(workbenchTheme());
    }

    @Override
    public Identifier defaultThemeId() {
        return WORKBENCH_ID;
    }

    private static MenuTheme bareTheme(Identifier id, Component name, ThemePalette palette) {
        return new MenuTheme(id, name, palette, null, null, new ThemeBackdrop.GradientOnly(),
                new ThemeCenterpiece.None(), null, ThemeSounds.DEFAULT);
    }
}
