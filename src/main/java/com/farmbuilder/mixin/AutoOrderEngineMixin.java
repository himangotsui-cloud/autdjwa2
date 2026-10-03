package com.farmbuilder.mixin;

import com.farmbuilder.ext.CreativeSupply;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In creative there is nothing to /order or /sell, so those server-command routines are skipped. */
@Pseudo
@Mixin(targets = "com.farmbuilder.order.AutoOrderEngine", remap = false)
public abstract class AutoOrderEngineMixin {

    @Inject(method = "startOrder", at = @At("HEAD"), cancellable = true, remap = false)
    private static void farmbuilder$skipOrder(String item, int amount, int mode, CallbackInfo ci) {
        if (CreativeSupply.active(MinecraftClient.getInstance())) {
            ci.cancel();
        }
    }

    @Inject(method = "startSellBuckets", at = @At("HEAD"), cancellable = true, remap = false)
    private static void farmbuilder$skipSell(CallbackInfo ci) {
        if (CreativeSupply.active(MinecraftClient.getInstance())) {
            ci.cancel();
        }
    }
}
