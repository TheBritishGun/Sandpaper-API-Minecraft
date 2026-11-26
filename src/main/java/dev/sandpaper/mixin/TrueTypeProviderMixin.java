package dev.sandpaper.mixin;

import com.mojang.blaze3d.platform.TextureUtil;
import dev.sandpaper.client.font.FontGuard;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Admits font bytes through FontGuard before FreeType. */
@Mixin(TrueTypeGlyphProviderDefinition.class)
public abstract class TrueTypeProviderMixin {
    @Redirect(
            method = "load(Lnet/minecraft/server/packs/resources/ResourceManager;)Lcom/mojang/blaze3d/font/GlyphProvider;",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/platform/TextureUtil;readResource(Ljava/io/InputStream;)Ljava/nio/ByteBuffer;"))
    private ByteBuffer sandpaper$admitFace(InputStream in) throws IOException {
        return FontGuard.admit(TextureUtil.readResource(in));
    }
}
