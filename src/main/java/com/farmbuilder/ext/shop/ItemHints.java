package com.farmbuilder.ext.shop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Heuristics: which shop category is an item likely in, and which buttons must never be pressed. */
public final class ItemHints {
    public enum Group {
        BLOCKS("block", "building", "build", "khối", "xây", "stone", "wood", "brick", "terracotta", "concrete"),
        REDSTONE("redstone", "mạch", "circuit", "mechanism", "technical"),
        FARM("farm", "crop", "plant", "seed", "nông", "garden", "agricult", "nature"),
        FOOD("food", "thức ăn", "cook", "kitchen"),
        TOOLS("tool", "dụng cụ", "weapon", "armor", "armour", "combat", "gear", "equipment"),
        MISC("misc", "other", "decor", "khác", "special", "utility");

        final String[] words;

        Group(String... words) {
            this.words = words;
        }
    }

    private static final Pattern NEXT = Pattern.compile("next|forward|tiếp|trang sau|siguiente|suivant|»|→|>>|›|▶|⏩");
    private static final Pattern PREV = Pattern.compile("prev|previous|back|trước|quay lại|volver|«|←|<<|‹|◀|⏪");
    /** Buttons that sell, close, delete or are just info: never click them while exploring. */
    private static final Pattern DANGEROUS = Pattern.compile(
            "close|exit|cancel|sell|bán|auction|delete|clear|reset|disable|logout|quit|đóng|thoát|hủy|"
                    + "balance|bal\\b|your money|info|help|search|filter|sort|stats|settings|"
                    + "page \\d|trang \\d|toggle|admin|report|vote|discord|store|rank|crate");

    private ItemHints() {
    }

    public static List<Group> groupsFor(String itemId) {
        String id = itemId == null ? "" : itemId.toLowerCase(Locale.ROOT);
        List<Group> g = new ArrayList<>();
        if (has(id, "redstone", "piston", "observer", "hopper", "repeater", "comparator", "dispenser", "dropper", "lever",
                "button", "pressure_plate", "tripwire", "rail", "target", "daylight", "note_block", "tnt", "slime_block",
                "honey_block", "lamp", "lectern", "sculk_sensor")) {
            g.add(Group.REDSTONE);
            g.add(Group.BLOCKS);
        } else if (has(id, "seeds", "wheat", "carrot", "potato", "beetroot", "melon", "pumpkin", "sugar_cane", "bamboo",
                "cactus", "kelp", "nether_wart", "bone_meal", "sapling", "berries", "cocoa", "hoe", "farmland",
                "composter", "hay_block", "honey", "egg", "leather", "feather")) {
            g.add(Group.FARM);
            g.add(Group.FOOD);
            g.add(Group.BLOCKS);
        } else if (has(id, "cooked_", "beef", "porkchop", "chicken", "mutton", "bread", "apple", "cookie", "pie", "stew", "steak", "fish", "salmon", "cod")) {
            g.add(Group.FOOD);
            g.add(Group.FARM);
        } else if (has(id, "pickaxe", "_axe", "shovel", "sword", "bow", "totem", "helmet", "chestplate", "leggings",
                "boots", "shield", "trident", "elytra", "potion", "bucket", "shears", "flint_and_steel")) {
            g.add(Group.TOOLS);
            g.add(Group.MISC);
        } else {
            g.add(Group.BLOCKS);
            g.add(Group.MISC);
        }
        return g;
    }

    /** Higher = try first. Returns -1 for buttons that must not be pressed. */
    public static int navScore(String targetId, ShopSlot nav) {
        if (isExcludedNav(nav)) {
            return -1;
        }
        String text = (nav.name + " " + nav.itemId).toLowerCase(Locale.ROOT);
        if (targetId == null) {
            return 10;
        }
        List<Group> wanted = groupsFor(targetId);
        for (int i = 0; i < wanted.size(); i++) {
            if (has(text, wanted.get(i).words)) {
                return 100 - i * 30;          // primary group 100, secondary 70 ...
            }
        }
        for (Group g : Group.values()) {
            if (has(text, g.words)) {
                return 20;                    // a known category, just not the likely one
            }
        }
        return 10;                            // unknown icon: still worth a look later
    }

    public static boolean isNext(ShopSlot s) {
        String n = s.name.toLowerCase(Locale.ROOT);
        return NEXT.matcher(n).find() && !PREV.matcher(n).find();
    }

    public static boolean isPrev(ShopSlot s) {
        return PREV.matcher(s.name.toLowerCase(Locale.ROOT)).find();
    }

    public static boolean isExcludedNav(ShopSlot s) {
        if (s.blankName() || s.isFiller()) {
            return true;
        }
        String n = s.name.toLowerCase(Locale.ROOT);
        if (DANGEROUS.matcher(n).find() || isPrev(s) || isNext(s)) {
            return true;
        }
        // lore that talks about selling (and not about opening/browsing) -> leave it alone
        String lore = Money.stripColors(String.join(" ", s.lore)).toLowerCase(Locale.ROOT);
        return lore.contains("sell") && !has(lore, "open", "browse", "view", "category", "see");
    }

    private static boolean has(String s, String... needles) {
        for (String n : needles) {
            if (s.contains(n)) return true;
        }
        return false;
    }
}
