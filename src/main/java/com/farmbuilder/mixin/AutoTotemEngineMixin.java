package com.farmbuilder.mixin;

import com.farmbuilder.ext.CreativeSupply;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No totem juggling needed in creative. */
@Pseudo
@Mixin(targets = "com.farmbuilder.safety.AutoTotemEngine", remap = false)
public abstract class AutoTotemEngineMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private static void farmbuilder$noTotemInCreative(MinecraftClient client, boolean enabled, CallbackInfo ci) {
        if (CreativeSupply.active(client)) {
            ci.cancel();
        }
    }
}
