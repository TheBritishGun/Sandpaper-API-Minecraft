package dev.sandpaper.api.theme;

import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;

@FunctionalInterface
public interface ThemeContributor {
    String ENTRYPOINT = "sandpaper-themes";

    List<MenuTheme> themes();

    default Identifier defaultThemeId() {
        return null;
    }

    default Map<Identifier, Identifier> pageThemes() {
        return Map.of();
    }
}
