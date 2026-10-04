package com.farmbuilder.ext.shop;

import com.farmbuilder.ext.ExtConfig;
import com.farmbuilder.ext.LegacyBridge;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

/**
 * /farmshop status | on | off | scan | buy &lt;item&gt; [count] | stop | catalog | sell on|off
 */
public final class ShopCommands {
    private ShopCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommandManager.literal("farmshop")
                .then(ClientCommandManager.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(ClientCommandManager.literal("on").executes(ctx -> setEnabled(ctx.getSource(), true)))
                .then(ClientCommandManager.literal("off").executes(ctx -> setEnabled(ctx.getSource(), false)))
                .then(ClientCommandManager.literal("scan").executes(ctx -> {
                    ShopEngine.startScan();
                    return 1;
                }))
                .then(ClientCommandManager.literal("stop").executes(ctx -> {
                    ShopEngine.stop();
                    return 1;
                }))
                .then(ClientCommandManager.literal("catalog").executes(ctx -> catalog(ctx.getSource())))
                .then(ClientCommandManager.literal("buy")
                        .then(ClientCommandManager.argument("item", StringArgumentType.word())
                                .executes(ctx -> {
                                    ShopEngine.buyManual(StringArgumentType.getString(ctx, "item"), 1);
                                    return 1;
                                })
                                .then(ClientCommandManager.argument("count", IntegerArgumentType.integer(1, 2304))
                                        .executes(ctx -> {
                                            ShopEngine.buyManual(StringArgumentType.getString(ctx, "item"),
                                                    IntegerArgumentType.getInteger(ctx, "count"));
                                            return 1;
                                        }))))
                .then(ClientCommandManager.literal("sell")
                        .then(ClientCommandManager.literal("on").executes(ctx -> legacySell(ctx.getSource(), true)))
                        .then(ClientCommandManager.literal("off").executes(ctx -> legacySell(ctx.getSource(), false))))
                .executes(ctx -> help(ctx.getSource())));
    }

    /** Turns shop mode on/off exactly like the menu toggle does. */
    public static void applyEnabled(boolean on) {
        ExtConfig.shopEnabled = on;
        ExtConfig.save();
        if (on) {
            // the builder only asks for materials when auto-order is on; shop mode replaces /order, so switch it on
            LegacyBridge.setConfigBoolean("autoOrder", true);
            LegacyBridge.setConfigBoolean("autoSellBuckets", false);
        }
    }

    private static int setEnabled(FabricClientCommandSource src, boolean on) {
        applyEnabled(on);
        src.sendFeedback(Text.literal("§6[AutoShop] " + LegacyBridge.tr("Shop (/shop mua đồ thiếu): ", "Shop (/shop auto-buy): ")
                + (on ? "§aON" : "§cOFF")));
        return 1;
    }

    private static int status(FabricClientCommandSource src) {
        ShopCatalog cat = ShopEngine.catalogOrNull();
        src.sendFeedback(Text.literal("§6[AutoShop] §fenabled=" + (ExtConfig.shopEnabled ? "§aON" : "§cOFF")
                + "§f  now=" + ShopEngine.statusLine()
                + "§f  command=/" + ExtConfig.shopCommand + "  balance=/" + ExtConfig.balanceCommand
                + "§f  known items=" + (cat == null ? 0 : cat.items.size())));
        return 1;
    }

    private static int catalog(FabricClientCommandSource src) {
        ShopCatalog cat = ShopEngine.catalogOrNull();
        if (cat == null || cat.items.isEmpty()) {
            src.sendFeedback(Text.literal("§e[AutoShop] " + LegacyBridge.tr("Chưa quét shop. Dùng /farmshop scan.", "No catalog yet. Run /farmshop scan.")));
            return 0;
        }
        src.sendFeedback(Text.literal("§6[AutoShop] §f" + cat.items.size() + " items known. Examples:"));
        int n = 0;
        for (ShopCatalog.Entry e : cat.items.values()) {
            src.sendFeedback(Text.literal("§7 - §f" + e.itemId + " §7$" + Money.format(e.price)
                    + " §8(" + (e.route.isEmpty() ? "root" : String.join(" > ", e.route)) + ")"));
            if (++n >= 15) break;
        }
        return cat.items.size();
    }

    private static int legacySell(FabricClientCommandSource src, boolean on) {
        ExtConfig.legacySell = on;
        ExtConfig.save();
        src.sendFeedback(Text.literal("§6[AutoShop] Old /sell-buckets routine: " + (on ? "§aON" : "§cOFF")));
        return 1;
    }

    private static int help(FabricClientCommandSource src) {
        src.sendFeedback(Text.literal("§6/farmshop on|off §7- shop mode (replaces /order)"));
        src.sendFeedback(Text.literal("§6/farmshop scan §7- walk the whole /shop and remember everything"));
        src.sendFeedback(Text.literal("§6/farmshop buy <item> [count] §7- test a purchase"));
        src.sendFeedback(Text.literal("§6/farmshop stop|status|catalog"));
        src.sendFeedback(Text.literal("§7Menu log for odd shops: logs/farmshop-scan.txt"));
        return 1;
    }
}
