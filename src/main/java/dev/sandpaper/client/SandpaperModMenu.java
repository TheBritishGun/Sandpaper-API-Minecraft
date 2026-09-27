package dev.sandpaper.client;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
// Loads only when Mod Menu does, so these imports are safe.
public final class SandpaperModMenu implements ModMenuApi {
    // Opens the shared settings screen; sandpaper's own page is first in the list.
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SandpaperConfigScreen::create;
    }
}
