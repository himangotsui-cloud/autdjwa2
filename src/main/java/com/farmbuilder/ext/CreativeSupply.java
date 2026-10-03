package com.farmbuilder.ext;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.world.GameMode;

/**
 * Creative-mode block supply.
 *
 * Active ONLY in singleplayer + creative gamemode. In survival (or on any server) {@link #active}
 * is false and the original FarmBuilder logic runs completely untouched.
 */
public final class CreativeSupply {
    /** What the mod's "how many do I have" checks report while supply is active. */
    public static final int UNLIMITED = 2304; // 36 slots * 64

    private CreativeSupply() {
    }

    public static boolean active(MinecraftClient c) {
        return ExtConfig.creativeSupply
                && c != null
                && c.player != null
                && c.interactionManager != null
                && c.isInSingleplayer()
                && c.interactionManager.getCurrentGameMode() == GameMode.CREATIVE;
    }

    /**
     * Makes sure {@code item} is selected in the hotbar, creating it from the creative menu if needed.
     *
     * @return true if the item is now in hand (caller should treat the request as satisfied);
     *         false if supply is not active, so the caller's normal survival logic must run.
     */
    public static boolean supply(MinecraftClient c, Item item) {
        if (!active(c) || item == null || item == Items.AIR) {
            return false;
        }
        ClientPlayerEntity player = c.player;
        PlayerInventory inv = player.getInventory();

        if (player.getMainHandStack().isOf(item)) {
            return true;
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(item)) {
                inv.setSelectedSlot(i);
                return true;
            }
        }

        int slot = pickHotbarSlot(inv);
        ItemStack stack = new ItemStack(item, Math.max(1, item.getMaxCount()));
        inv.setStack(slot, stack);                                  // visible immediately on the client
        c.interactionManager.clickCreativeStack(stack.copy(), 36 + slot); // tell the integrated server (hotbar = 36..44)
        inv.setSelectedSlot(slot);
        return true;
    }

    /** Empty slot first, then a slot holding plain blocks, then whatever is selected (never hunts for tools). */
    private static int pickHotbarSlot(PlayerInventory inv) {
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isEmpty()) {
                return i;
            }
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() instanceof BlockItem) {
                return i;
            }
        }
        return inv.getSelectedSlot();
    }
}
