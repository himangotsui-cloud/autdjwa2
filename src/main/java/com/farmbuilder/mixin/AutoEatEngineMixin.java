package com.farmbuilder.mixin;

import com.farmbuilder.ext.CreativeSupply;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Creative players don't get hungry: report "not eating" so the build loop is never paused for food. */
@Pseudo
@Mixin(targets = "com.farmbuilder.safety.AutoEatEngine", remap = false)
public abstract class AutoEatEngineMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private static void farmbuilder$noEatInCreative(MinecraftClient client, boolean enabled, CallbackInfoReturnable<Boolean> cir) {
        if (CreativeSupply.active(client)) {
            cir.setReturnValue(false);
        }
    }
}
