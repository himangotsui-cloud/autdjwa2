package com.farmbuilder.ext.shop;

import java.util.Arrays;
import java.util.List;

/** Tunables for the shop brain. */
public final class ShopSettings {
    public String shopCommand = "shop";
    public String balanceCommand = "bal";
    /** Ask for the balance before buying and refuse if the purchase cannot be afforded. */
    public boolean checkBalance = true;
    /** Never spend more than this in one request (0 = no cap). */
    public double maxSpend = 0;
    public List<String> failPhrases = Arrays.asList(
            "not enough", "can't afford", "cannot afford", "insufficient", "don't have enough",
            "do not have enough", "need more money", "không đủ", "khong du", "thiếu tiền",
            "inventory is full", "inventory full", "no space", "túi đồ đầy");
    public int maxScreens = 60;
    public int maxActions = 400;
    public int maxTicks = 20 * 120;
    /** How many times the brain may send the shop command in one job (stops open/close loops). */
    public int maxOpens = 25;
    public int settleMinTicks = 3;
    public int waitMaxTicks = 40;
    public int maxDepth = 3;
    public int maxPagesPerCategory = 40;
}
