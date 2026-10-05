package com.farmbuilder.ext.shop;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses prices / balances out of the free-form text servers put in item lore and chat. */
public final class Money {
    private static final Pattern NUMBER = Pattern.compile("(\\d[\\d.,]*)(?:\\s?([kKmMbBtT])(?![a-zA-Z]))?");
    private static final Pattern AFTER_CURRENCY = Pattern.compile("[$¢₫đ]\\s?(\\d[\\d.,]*)(?:\\s?([kKmMbBtT])(?![a-zA-Z]))?");
    private static final Pattern LABELLED = Pattern.compile(
            "(?i)(buy|mua|cost|price|giá|purchase)\\s*(?:for|:|-|=|→|»)+\\s*\\D{0,3}(\\d[\\d.,]*)(?:\\s?([kKmMbBtT])(?![a-zA-Z]))?");
    private static final Pattern COLOR = Pattern.compile("(?i)§[0-9a-fk-orx]");

    private Money() {
    }

    public static String stripColors(String s) {
        return s == null ? "" : COLOR.matcher(s).replaceAll("");
    }

    /** First number in the text (supports 1,234.50 / 1.234,50 / 1.5k / 2m), or NaN. */
    public static double parse(String text) {
        if (text == null) {
            return Double.NaN;
        }
        Matcher m = NUMBER.matcher(stripColors(text));
        return m.find() ? token(m.group(1), m.group(2)) : Double.NaN;
    }

    /** The number written right after a currency symbol if there is one, else the first number. */
    public static double parseCurrency(String text) {
        String clean = stripColors(text);
        Matcher m = AFTER_CURRENCY.matcher(clean);
        if (m.find()) {
            return token(m.group(1), m.group(2));
        }
        return parse(clean);
    }

    static double token(String tok, String suffix) {
        String t = tok.replaceAll("[.,]+$", "");
        boolean dot = t.indexOf('.') >= 0, comma = t.indexOf(',') >= 0;
        if (dot && comma) {
            char decimal = t.lastIndexOf('.') > t.lastIndexOf(',') ? '.' : ',';
            char group = decimal == '.' ? ',' : '.';
            t = t.replace(String.valueOf(group), "").replace(decimal, '.');
        } else if (comma) {
            t = t.matches("\\d{1,3}(,\\d{3})+") ? t.replace(",", "") : t.replace(',', '.');
        } else if (dot && t.indexOf('.') != t.lastIndexOf('.')) {
            t = t.replace(".", "");           // 1.234.567
        }
        double v;
        try {
            v = Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        if (suffix != null && !suffix.isEmpty()) {
            switch (Character.toLowerCase(suffix.charAt(0))) {
                case 'k': v *= 1e3; break;
                case 'm': v *= 1e6; break;
                case 'b': v *= 1e9; break;
                case 't': v *= 1e12; break;
                default: break;
            }
        }
        return v;
    }

    private static final String[] NOT_A_BUY_PRICE = {"sell", "bán", "balance", "you have", "stock", "limit", "owned", "your money"};

    /** Price written in an item's lore, or NaN if this item has no recognisable buy price. */
    public static double findPrice(List<String> lore) {
        if (lore == null) {
            return Double.NaN;
        }
        // pass 1: explicit price / cost lines
        for (String line : lore) {
            String low = stripColors(line).toLowerCase(Locale.ROOT);
            if (containsAny(low, NOT_A_BUY_PRICE)) continue;
            if (containsAny(low, "price", "cost", "giá", "$", "coins", "¢")) {
                double v = parseCurrency(line);
                if (!Double.isNaN(v)) return v;
            }
        }
        // pass 2: "buy ... $x" style lines (need a currency symbol so "buy 64" isn't read as a price)
        for (String line : lore) {
            String low = stripColors(line).toLowerCase(Locale.ROOT);
            if (containsAny(low, NOT_A_BUY_PRICE)) continue;
            if (containsAny(low, "buy", "mua") && AFTER_CURRENCY.matcher(low).find()) {
                double v = parseCurrency(line);
                if (!Double.isNaN(v)) return v;
            }
        }
        // pass 3: "Buy: 10", "Cost - 25 coins": a label, a colon/dash, then the number (no currency symbol needed)
        for (String line : lore) {
            String low = stripColors(line).toLowerCase(Locale.ROOT);
            if (containsAny(low, NOT_A_BUY_PRICE)) continue;
            Matcher m = LABELLED.matcher(stripColors(line));
            if (m.find()) {
                double v = token(m.group(2), m.group(3));
                if (!Double.isNaN(v)) return v;
            }
        }
        return Double.NaN;
    }

    /** Cost of ONE item given the lore (handles "each", "per stack", and "price for the shown amount"). */
    public static double unitPrice(List<String> lore, int shownCount) {
        double price = findPrice(lore);
        if (Double.isNaN(price)) {
            return Double.NaN;
        }
        String all = stripColors(String.join(" ", lore)).toLowerCase(Locale.ROOT);
        if (containsAny(all, "each", "per item", "/item", "per piece", "mỗi", "/1")) {
            return price;
        }
        if (containsAny(all, "stack", "x64", "per 64")) {
            return price / (shownCount > 1 ? shownCount : 64);
        }
        return price / Math.max(1, shownCount);
    }

    private static boolean containsAny(String s, String... needles) {
        for (String n : needles) {
            if (s.contains(n)) return true;
        }
        return false;
    }

    public static String format(double v) {
        if (Double.isNaN(v)) return "?";
        if (v >= 1e9) return String.format(Locale.ROOT, "%.2fB", v / 1e9);
        if (v >= 1e6) return String.format(Locale.ROOT, "%.2fM", v / 1e6);
        if (v >= 1e4) return String.format(Locale.ROOT, "%.1fK", v / 1e3);
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }
}
