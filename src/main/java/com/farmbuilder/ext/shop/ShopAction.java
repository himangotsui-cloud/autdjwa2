package com.farmbuilder.ext.shop;

/** What the brain wants done this tick. */
public final class ShopAction {
    public enum Type { WAIT, COMMAND, CLICK, CLOSE }

    public final Type type;
    public final int slot;
    public final boolean shift;
    public final String text;

    private ShopAction(Type type, int slot, boolean shift, String text) {
        this.type = type;
        this.slot = slot;
        this.shift = shift;
        this.text = text;
    }

    public static final ShopAction WAIT = new ShopAction(Type.WAIT, -1, false, "");
    public static final ShopAction CLOSE = new ShopAction(Type.CLOSE, -1, false, "");

    public static ShopAction command(String cmd) {
        return new ShopAction(Type.COMMAND, -1, false, cmd);
    }

    public static ShopAction click(int slot, boolean shift) {
        return new ShopAction(Type.CLICK, slot, shift, "");
    }

    @Override
    public String toString() {
        return type + (type == Type.CLICK ? " slot=" + slot + (shift ? " shift" : "") : type == Type.COMMAND ? " /" + text : "");
    }
}
