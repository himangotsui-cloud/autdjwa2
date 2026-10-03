package com.farmbuilder.mixin;

import com.farmbuilder.ext.CreativeSupply;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks the original (precompiled) AutoBuildCore. Only does anything in singleplayer creative;
 * otherwise both injectors fall straight through to the original survival code.
 */
@Pseudo
@Mixin(targets = "com.farmbuilder.build.AutoBuildCore", remap = false)
public abstract class AutoBuildCoreMixin {

    /** "Get this item into my hand" -> create it from the creative menu instead of searching the inventory. */
    @Inject(method = "ensureItemInHand", at = @At("HEAD"), cancellable = true, remap = false)
    private static void farmbuilder$creativeEnsure(MinecraftClient client, Item item, CallbackInfoReturnable<Boolean> cir) {
        if (CreativeSupply.supply(client, item)) {
            cir.setReturnValue(true);
        }
    }

    /** "How many of this do I have" -> plenty, so the builder never thinks it ran out of materials. */
    @Inject(method = "countItemInInventory", at = @At("HEAD"), cancellable = true, remap = false)
    private static void farmbuilder$creativeCount(MinecraftClient client, String itemId, CallbackInfoReturnable<Integer> cir) {
        if (CreativeSupply.active(client)) {
            cir.setReturnValue(CreativeSupply.UNLIMITED);
        }
    }
}
