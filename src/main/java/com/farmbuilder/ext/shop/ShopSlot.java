package com.farmbuilder.ext.shop;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** One non-empty slot of the shop container, reduced to plain data. */
public final class ShopSlot {
    /** Info icons that show a money amount but are not for sale. */
    private static final Pattern NOT_FOR_SALE = Pattern.compile("(?i)balance|your money|\\bbal\\b|wallet|số dư|tiền của bạn|your coins|purse");
    private static final Pattern DIGITS_AND_BRACKETS = Pattern.compile("[0-9()\\[\\]/:]+");

    public final int index;
    /** Registry path without namespace, e.g. "oak_planks". */
    public final String itemId;
    /** Display name with colour codes removed. */
    public final String name;
    public final List<String> lore;
    public final int count;

    public ShopSlot(int index, String itemId, String name, List<String> lore, int count) {
        this.index = index;
        this.itemId = itemId;
        this.name = Money.stripColors(name).trim();
        this.lore = lore;
        this.count = count;
    }

    /** Stable identity of a button across visits (slot indexes and page numbers may change). */
    public String key() {
        String n = DIGITS_AND_BRACKETS.matcher(name.toLowerCase(Locale.ROOT)).replaceAll(" ").replaceAll("\\s+", " ").trim();
        return itemId + "|" + n;
    }

    public boolean blankName() {
        return name.isEmpty() || name.replace(" ", "").isEmpty();
    }

    /** Decorative border/filler pane. */
    public boolean isFiller() {
        return blankName() && (itemId.endsWith("_pane") || itemId.equals("barrier") || itemId.endsWith("glass"));
    }

    public double price() {
        return Money.findPrice(lore);
    }

    /** Lore that says "click to buy / price / cost" (and is not about opening a category): a product, even with no parsable price. */
    public boolean looksPurchasable() {
        String l = Money.stripColors(String.join(" ", lore)).toLowerCase(Locale.ROOT);
        boolean buyish = l.contains("buy") || l.contains("mua") || l.contains("purchase") || l.contains("price")
                || l.contains("cost") || l.contains("giá");
        boolean opens = l.contains("open") || l.contains("browse") || l.contains("category") || l.contains("view")
                || l.contains("see ") || l.contains("xem");
        return buyish && !opens;
    }

    public boolean isProduct() {
        return !NOT_FOR_SALE.matcher(name).find() && !Double.isNaN(price());
    }
}
