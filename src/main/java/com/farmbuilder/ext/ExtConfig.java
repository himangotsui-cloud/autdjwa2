package com.farmbuilder.ext;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Small settings file for the extensions: config/farmbuilder-ext.properties */
public final class ExtConfig {
    /** In singleplayer creative, pull blocks straight from the creative menu instead of ordering/farming them. */
    public static volatile boolean creativeSupply = true;
    /** Drag a schematic onto the game window -> convert and load it automatically. */
    public static volatile boolean autoLoadDropped = true;

    /** Survival/SMP: when materials run out, use /shop (scan, find, buy) instead of /order. Toggled from the GUI. */
    public static volatile boolean shopEnabled = false;
    /** The old "/sell empty buckets" routine was removed from the menu; set true in the file to bring it back. */
    public static volatile boolean legacySell = false;
    public static volatile String shopCommand = "shop";
    public static volatile String balanceCommand = "bal";
    /** Ask /bal first and refuse purchases that cost more than the balance. */
    public static volatile boolean shopCheckBalance = true;
    /** Never spend more than this per request (0 = no cap). */
    public static volatile double shopMaxSpend = 0;
    /** After a failed shop attempt for an item, wait this long before trying that item again. */
    public static volatile int shopRetryCooldownSec = 120;
    /** Write every menu the shop bot sees to logs/farmshop-scan.txt (useful when a server's shop is unusual). */
    public static volatile boolean shopDebugLog = true;

    /** Pressing this key (or Esc) while the shop bot is working stops it AND turns Shop mode off. GLFW code, default Delete. */
    public static volatile int shopStopKey = 261;
    /** After this many failed shop jobs in a row, Shop mode switches itself off. */
    public static volatile int shopMaxFailures = 3;

    private ExtConfig() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("farmbuilder-ext.properties");
    }

    public static void load() {
        Path f = file();
        if (!Files.exists(f)) {
            save();
            return;
        }
        try (InputStream in = Files.newInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            creativeSupply = Boolean.parseBoolean(p.getProperty("creativeSupply", "true"));
            autoLoadDropped = Boolean.parseBoolean(p.getProperty("autoLoadDropped", "true"));
            shopEnabled = Boolean.parseBoolean(p.getProperty("shopEnabled", "false"));
            legacySell = Boolean.parseBoolean(p.getProperty("legacySell", "false"));
            shopCommand = p.getProperty("shopCommand", "shop").trim().replaceFirst("^/", "");
            balanceCommand = p.getProperty("balanceCommand", "bal").trim().replaceFirst("^/", "");
            shopCheckBalance = Boolean.parseBoolean(p.getProperty("shopCheckBalance", "true"));
            shopMaxSpend = parseDouble(p.getProperty("shopMaxSpend", "0"));
            shopRetryCooldownSec = (int) parseDouble(p.getProperty("shopRetryCooldownSec", "120"));
            shopDebugLog = Boolean.parseBoolean(p.getProperty("shopDebugLog", "true"));
            shopStopKey = (int) parseDouble(p.getProperty("shopStopKey", "261"));
            shopMaxFailures = (int) parseDouble(p.getProperty("shopMaxFailures", "3"));
        } catch (IOException e) {
            System.err.println("[FarmBuilder] Could not read " + f + ": " + e.getMessage());
        }
    }

    public static void save() {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Properties p = new Properties();
            p.setProperty("creativeSupply", Boolean.toString(creativeSupply));
            p.setProperty("autoLoadDropped", Boolean.toString(autoLoadDropped));
            p.setProperty("shopEnabled", Boolean.toString(shopEnabled));
            p.setProperty("legacySell", Boolean.toString(legacySell));
            p.setProperty("shopCommand", shopCommand);
            p.setProperty("balanceCommand", balanceCommand);
            p.setProperty("shopCheckBalance", Boolean.toString(shopCheckBalance));
            p.setProperty("shopMaxSpend", Double.toString(shopMaxSpend));
            p.setProperty("shopRetryCooldownSec", Integer.toString(shopRetryCooldownSec));
            p.setProperty("shopDebugLog", Boolean.toString(shopDebugLog));
            p.setProperty("shopStopKey", Integer.toString(shopStopKey));
            p.setProperty("shopMaxFailures", Integer.toString(shopMaxFailures));
            try (OutputStream out = Files.newOutputStream(f)) {
                p.store(out, "FarmBuilder extensions");
            }
        } catch (IOException e) {
            System.err.println("[FarmBuilder] Could not write " + f + ": " + e.getMessage());
        }
    }

    private static double parseDouble(String v) {
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
