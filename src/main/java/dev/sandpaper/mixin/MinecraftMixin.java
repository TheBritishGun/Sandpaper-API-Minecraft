package dev.sandpaper.mixin;
import dev.sandpaper.client.font.FontPackSourceInstaller;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "<init>", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/server/packs/repository/PackRepository;reload()V"))
    private void sandpaper$addFontPackSource(CallbackInfo ci) {
        Minecraft client = (Minecraft) (Object) this;
        FontPackSourceInstaller.addFontPackSource(client.getResourcePackRepository());
    }
}
