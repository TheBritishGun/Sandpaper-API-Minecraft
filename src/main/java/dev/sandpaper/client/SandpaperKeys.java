package dev.sandpaper.client;
import com.mojang.blaze3d.platform.InputConstants;
import dev.sandpaper.Sandpaper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
// One key opens settings for every installed contributor; add no per-mod key.
public final class SandpaperKeys {
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath(Sandpaper.MOD_ID, "main"));
    public static KeyMapping openSettings;
    private SandpaperKeys() {
    }
    // Call once, from the client initializer; a second call adds a duplicate listener.
    public static void register() {
        openSettings = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.sandpaper.open_settings",
                InputConstants.Type.KEYSYM,
                InputConstants.getKey("key.keyboard.k").getValue(),
                CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(SandpaperKeys::handleKeys);
    }
    private static void handleKeys(Minecraft client) {
        while (openSettings.consumeClick()) {
            // No screen is open when a keybind fires here; null returns to the game.
            client.setScreenAndShow(SandpaperConfigScreen.create(null));
        }
    }
}
