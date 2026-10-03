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
            try (OutputStream out = Files.newOutputStream(f)) {
                p.store(out, "FarmBuilder extensions");
            }
        } catch (IOException e) {
            System.err.println("[FarmBuilder] Could not write " + f + ": " + e.getMessage());
        }
    }
}
