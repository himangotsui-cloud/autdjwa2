package com.farmbuilder.mixin;

import com.farmbuilder.ext.CreativeSupply;
import com.farmbuilder.ext.ExtConfig;
import com.farmbuilder.ext.shop.ShopEngine;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Re-routes the original /order and /sell routines:
 *   - singleplayer creative: nothing to order or sell (blocks come from the creative menu)
 *   - survival with Shop mode ON: "/order" becomes "open /shop, find the item, buy it"
 *   - the old "/sell buckets" routine is removed (unless legacySell is switched on in the config file)
 */
@Pseudo
@Mixin(targets = "com.farmbuilder.order.AutoOrderEngine", remap = false)
public abstract class AutoOrderEngineMixin {

    /** (item, requiredTotal, haveNow) - same meaning the original gives them. */
    @Inject(method = "startOrder", at = @At("HEAD"), cancellable = true, remap = false)
    private static void farmbuilder$redirectOrder(String item, int required, int have, CallbackInfo ci) {
        if (CreativeSupply.active(MinecraftClient.getInstance())) {
            ci.cancel();
        } else if (ExtConfig.shopEnabled) {
            ci.cancel();
            ShopEngine.request(item, required, have);
        }
    }

    @Inject(method = "startSellBuckets", at = @At("HEAD"), cancellable = true, remap = false)
    private static void farmbuilder$noSell(CallbackInfo ci) {
        if (!ExtConfig.legacySell || CreativeSupply.active(MinecraftClient.getInstance())) {
            ci.cancel();
        }
    }

    /** While the shop bot is working, the builder must see "busy" so it waits instead of walking away. */
    @Inject(method = "isBusy", at = @At("RETURN"), cancellable = true, remap = false)
    private static void farmbuilder$busyWhileShopping(CallbackInfoReturnable<Boolean> cir) {
        if (ShopEngine.isBusy()) {
            cir.setReturnValue(true);
        }
    }
}
