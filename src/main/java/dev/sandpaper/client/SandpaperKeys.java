package dev.sandpaper.client;
import com.mojang.blaze3d.platform.InputConstants;
import dev.sandpaper.Sandpaper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
public final class SandpaperKeys {
    public static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(
                    Identifier.fromNamespaceAndPath(Sandpaper.MOD_ID, "main"));
    public static KeyMapping openSettings;
    private SandpaperKeys() {
    }
    public static void register() {
        openSettings = KeyMappingHelper.registerKeyMapping(openSettingsMapping());
        ClientTickEvents.END_CLIENT_TICK.register(SandpaperKeys::handleKeys);
    }
    static KeyMapping openSettingsMapping() {
        return new KeyMapping(
                "key.sandpaper.open_settings",
                InputConstants.Type.KEYBOARD,
                InputConstants.getKey("key.keyboard.k").getValue(),
                CATEGORY);
    }
    private static void handleKeys(Minecraft client) {
        while (openSettings.consumeClick()) {
            client.setScreenAndShow(SandpaperConfigScreen.create(null));
        }
    }
}
