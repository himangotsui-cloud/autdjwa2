package com.farmbuilder.ext;

import com.farmbuilder.ext.schem.SchematicConverter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Finds schematic files, converts them off-thread, and loads the result into the builder. */
public final class SchematicImporter {
    private SchematicImporter() {
    }

    /** Same folder the mod already uses for blueprints (.minecraft/farms). */
    public static Path farmsDir() {
        return FabricLoader.getInstance().getGameDir().resolve("farms");
    }

    /** Litematica's default folder, so schematics you already have just work. */
    public static Path litematicaDir() {
        return FabricLoader.getInstance().getGameDir().resolve("schematics");
    }

    /** Where dropped files are kept. */
    public static Path dropDir() {
        return FabricLoader.getInstance().getGameDir().resolve("farmschem");
    }

    public static List<Path> listSchematics() {
        List<Path> out = new ArrayList<>();
        for (Path dir : new Path[]{litematicaDir(), dropDir()}) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> Files.isRegularFile(p) && SchematicConverter.isSchematicFile(p.getFileName().toString()))
                        .forEach(out::add);
            } catch (IOException ignored) {
                // unreadable folder -> just skip it
            }
        }
        out.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
        return out;
    }

    /** Accepts a full path, or a file name (with or without extension) from the schematic folders. */
    public static Path resolve(String arg) {
        String a = arg.trim();
        if (a.startsWith("\"") && a.endsWith("\"") && a.length() > 1) {
            a = a.substring(1, a.length() - 1);
        }
        try {
            Path direct = Path.of(a);
            if (Files.isRegularFile(direct)) {
                return direct;
            }
        } catch (RuntimeException ignored) {
            // not a valid path string -> fall through to folder search
        }
        for (Path dir : new Path[]{litematicaDir(), dropDir()}) {
            for (String ext : new String[]{"", ".litematic", ".schem", ".nbt"}) {
                Path p = dir.resolve(a + ext);
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    /** Copies a dropped file into the farmschem folder (best effort) and returns the copy, or the original. */
    public static Path keepCopy(Path src) {
        try {
            Files.createDirectories(dropDir());
            Path dst = dropDir().resolve(src.getFileName().toString());
            if (!dst.toAbsolutePath().equals(src.toAbsolutePath())) {
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            return dst;
        } catch (IOException e) {
            return src;
        }
    }

    /** Converts on a background thread (big schematics take a moment), then optionally loads on the game thread. */
    public static void importAsync(Path file, boolean load) {
        say("§e[FarmSchem] " + LegacyBridge.tr("Đang chuyển ", "Converting ") + file.getFileName() + " ...");
        Thread t = new Thread(() -> {
            try {
                SchematicConverter.Result r = SchematicConverter.convert(file, farmsDir(), null);
                say("§a[FarmSchem] " + r.format + ": " + r.blocks + " blocks (" + r.sizeX + "x" + r.sizeY + "x" + r.sizeZ
                        + ") -> " + LegacyBridge.tr("bản vẽ", "blueprint") + " §f" + r.name);
                MinecraftClient.getInstance().execute(() -> {
                    if (!load) {
                        say("§7[FarmSchem] /farmbuild load " + r.name);
                        return;
                    }
                    try {
                        LegacyBridge.loadBlueprint(r.name);
                        say("§a[FarmSchem] " + LegacyBridge.tr("Đã nạp. Dùng /farmbuild start để xây.",
                                "Loaded. Use /farmbuild start to build."));
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        say("§c[FarmSchem] " + LegacyBridge.tr("Không nạp được: ", "Could not load: ") + e);
                    }
                });
            } catch (IOException e) {
                say("§c[FarmSchem] " + e.getMessage());
            } catch (RuntimeException | OutOfMemoryError e) {
                say("§c[FarmSchem] " + LegacyBridge.tr("Lỗi đọc schematic: ", "Failed to read schematic: ") + e);
            }
        }, "FarmBuilder-SchematicImport");
        t.setDaemon(true);
        t.start();
    }

    /** Thread-safe chat line (falls back to the log when no world is open, e.g. on the title screen). */
    public static void say(String msg) {
        MinecraftClient c = MinecraftClient.getInstance();
        c.execute(() -> {
            if (c.player != null) {
                c.player.sendMessage(Text.literal(msg), false);
            } else {
                System.out.println("[FarmBuilder] " + msg.replaceAll("§.", ""));
            }
        });
    }
}
