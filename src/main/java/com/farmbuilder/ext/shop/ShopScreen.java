package com.farmbuilder.ext.shop;

import java.util.List;

/** Snapshot of the shop container that is currently open. */
public final class ShopScreen {
    /** Window id (changes whenever the server opens a new menu). */
    public final int id;
    public final String title;
    public final List<ShopSlot> slots;
    private final int sig;

    public ShopScreen(int id, String title, List<ShopSlot> slots) {
        this.id = id;
        this.title = Money.stripColors(title);
        this.slots = slots;
        int h = 17;
        for (ShopSlot s : slots) {
            h = h * 31 + s.index;
            h = h * 31 + s.itemId.hashCode();
            h = h * 31 + s.name.hashCode();
            h = h * 31 + s.count;
        }
        this.sig = h;
    }

    /** Hash of what is visible - changes when a page is turned inside the same window. */
    public int sig() {
        return sig;
    }

    public ShopSlot slotWithKey(String key) {
        for (ShopSlot s : slots) {
            if (s.key().equals(key)) return s;
        }
        return null;
    }
}
