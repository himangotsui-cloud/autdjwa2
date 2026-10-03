package com.farmbuilder.ext.schem;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts Litematica (.litematic), Sponge (.schem, v1-v3) and vanilla structure (.nbt)
 * files into FarmBuilder blueprint JSON ("version" 2.0, same layout as farms/32.json).
 *
 * No Minecraft classes are used, so this can be unit-tested stand-alone.
 */
public final class SchematicConverter {
    /** Safety cap; the build planner works on the whole block list in memory. */
    public static final int MAX_BLOCKS = 1_000_000;

    private static final Set<String> SKIPPED = Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void");

    private SchematicConverter() {
    }

    public static boolean isSchematicFile(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.endsWith(".litematic") || n.endsWith(".schem") || n.endsWith(".nbt");
    }

    public static final class Result {
        public final String name;
        public final Path file;
        public final String format;
        public final int blocks;
        public final int sizeX, sizeY, sizeZ;

        Result(String name, Path file, String format, int blocks, int sx, int sy, int sz) {
            this.name = name;
            this.file = file;
            this.format = format;
            this.blocks = blocks;
            this.sizeX = sx;
            this.sizeY = sy;
            this.sizeZ = sz;
        }
    }

    // ------------------------------------------------------------------------------------
    // public API
    // ------------------------------------------------------------------------------------

    /**
     * @param input     schematic file
     * @param farmsDir  output folder (the mod's "farms" folder)
     * @param nameOrNull blueprint name, or null to derive it from the file name
     */
    public static Result convert(Path input, Path farmsDir, String nameOrNull) throws IOException {
        Map<String, Object> root = NbtReader.read(input);
        Source src = detect(root);

        // pass 1: real (non-air) bounds + count, so empty padding in the schematic is trimmed
        int[] mn = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] mx = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        int[] count = {0};
        src.scan((x, y, z, s) -> {
            if (++count[0] > MAX_BLOCKS) {
                throw new IOException("Schematic has more than " + MAX_BLOCKS
                        + " blocks - too big to build in one go. Split it into smaller parts.");
            }
            if (x < mn[0]) mn[0] = x;
            if (y < mn[1]) mn[1] = y;
            if (z < mn[2]) mn[2] = z;
            if (x > mx[0]) mx[0] = x;
            if (y > mx[1]) mx[1] = y;
            if (z > mx[2]) mx[2] = z;
        });
        if (count[0] == 0) {
            throw new IOException("Schematic contains no solid blocks (only air).");
        }
        int sx = mx[0] - mn[0] + 1, sy = mx[1] - mn[1] + 1, sz = mx[2] - mn[2] + 1;

        String name = sanitizeName(nameOrNull != null ? nameOrNull : stripExtension(input.getFileName().toString()));
        Files.createDirectories(farmsDir);
        Path out = farmsDir.resolve(name + ".json");
        Path tmp = farmsDir.resolve(name + ".json.tmp");

        // pass 2: stream the JSON out (no giant in-memory tree)
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write("{\n  \"name\": " + quote(name) + ",\n");
            w.write("  \"version\": \"2.0\",\n");
            w.write("  \"createdAt\": " + System.currentTimeMillis() + ",\n");
            w.write("  \"size\": {\"x\": " + sx + ", \"y\": " + sy + ", \"z\": " + sz + "},\n");
            w.write("  \"blocks\": [");
            boolean[] first = {true};
            src.scan((x, y, z, s) -> {
                w.write(first[0] ? "\n" : ",\n");
                first[0] = false;
                w.write("    {\"rel\": {\"x\": " + (x - mn[0]) + ", \"y\": " + (y - mn[1]) + ", \"z\": " + (z - mn[2])
                        + "}, \"block\": " + s.idJson + ", \"state\": " + s.stateJson + "}");
            });
            w.write("\n  ]\n}\n");
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
        return new Result(name, out, src.format(), count[0], sx, sy, sz);
    }

    /** Same sanitising rule the mod uses for blueprint file names. */
    public static String sanitizeName(String raw) {
        String n = raw.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        return n.isEmpty() ? "schematic" : n;
    }

    private static String stripExtension(String file) {
        String l = file.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".litematic", ".schem", ".nbt"}) {
            if (l.endsWith(ext)) {
                return file.substring(0, file.length() - ext.length());
            }
        }
        return file;
    }

    // ------------------------------------------------------------------------------------
    // format detection
    // ------------------------------------------------------------------------------------

    private static Source detect(Map<String, Object> root) throws IOException {
        if (root.get("Regions") instanceof Map) {
            return new LitematicSource(root);
        }
        Map<String, Object> sponge = NbtReader.compound(root.get("Schematic"));
        if (sponge != null && sponge.containsKey("Width")) {
            return new SpongeSource(sponge);
        }
        if (root.containsKey("Width") && root.containsKey("Length")
                && (root.containsKey("BlockData") || root.containsKey("Blocks"))) {
            if (root.get("Blocks") instanceof byte[]) {
                throw new IOException("Old MCEdit .schematic files (numeric block IDs) are not supported. "
                        + "Re-save it as .litematic or .schem.");
            }
            return new SpongeSource(root);
        }
        if (root.containsKey("size") && root.containsKey("blocks")
                && (root.containsKey("palette") || root.containsKey("palettes"))) {
            return new StructureSource(root);
        }
        throw new IOException("Unrecognised schematic format (expected .litematic, Sponge .schem or structure .nbt).");
    }

    // ------------------------------------------------------------------------------------
    // shared types
    // ------------------------------------------------------------------------------------

    @FunctionalInterface
    interface Emit {
        void block(int x, int y, int z, State s) throws IOException;
    }

    interface Source {
        String format();

        /** Calls emit for every non-air block, in schematic-local coordinates. May be called more than once. */
        void scan(Emit emit) throws IOException;
    }

    /** One palette entry, with its JSON fragments pre-rendered. */
    static final class State {
        final boolean air;
        final String idJson;
        final String stateJson;

        State(String id, Map<String, String> props) {
            String full = id.indexOf(':') >= 0 ? id : "minecraft:" + id;
            this.air = SKIPPED.contains(full);
            this.idJson = quote(full);
            StringBuilder sb = new StringBuilder("{");
            boolean f = true;
            for (Map.Entry<String, String> e : props.entrySet()) {
                if (!f) sb.append(", ");
                f = false;
                sb.append(quote(e.getKey())).append(": ").append(quote(e.getValue()));
            }
            this.stateJson = sb.append('}').toString();
        }
    }

    /** NBT compound {Name, Properties{...}} (Litematica and structure files). */
    private static State stateFromCompound(Object o) throws IOException {
        Map<String, Object> c = NbtReader.compound(o);
        if (c == null || !(c.get("Name") instanceof String)) {
            throw new IOException("Corrupt block palette entry");
        }
        Map<String, String> props = new LinkedHashMap<>();
        Map<String, Object> p = NbtReader.compound(c.get("Properties"));
        if (p != null) {
            for (Map.Entry<String, Object> e : p.entrySet()) {
                props.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return new State((String) c.get("Name"), props);
    }

    /** "minecraft:oak_stairs[facing=north,half=bottom]" */
    static State stateFromString(String s) {
        int br = s.indexOf('[');
        if (br < 0) {
            return new State(s.trim(), new LinkedHashMap<>());
        }
        String id = s.substring(0, br).trim();
        int end = s.lastIndexOf(']');
        String inner = s.substring(br + 1, end > br ? end : s.length());
        Map<String, String> props = new LinkedHashMap<>();
        for (String pair : inner.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                props.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return new State(id, props);
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    // ------------------------------------------------------------------------------------
    // Litematica (.litematic)
    // ------------------------------------------------------------------------------------

    private static final class LitematicSource implements Source {
        private final List<Region> regions = new ArrayList<>();

        private static final class Region {
            int ox, oy, oz;      // lowest corner in schematic space
            int ax, ay, az;      // absolute sizes
            State[] palette;
            long[] bits;
            int bitsPerEntry;
        }

        LitematicSource(Map<String, Object> root) throws IOException {
            Map<String, Object> regs = NbtReader.compound(root.get("Regions"));
            for (Map.Entry<String, Object> e : regs.entrySet()) {
                Map<String, Object> r = NbtReader.compound(e.getValue());
                Map<String, Object> pos = NbtReader.compound(r.get("Position"));
                Map<String, Object> size = NbtReader.compound(r.get("Size"));
                if (pos == null || size == null || !(r.get("BlockStates") instanceof long[])) {
                    throw new IOException("Litematic region '" + e.getKey() + "' is missing data");
                }
                int px = NbtReader.intOf(pos.get("x"), 0), py = NbtReader.intOf(pos.get("y"), 0), pz = NbtReader.intOf(pos.get("z"), 0);
                int sx = NbtReader.intOf(size.get("x"), 0), sy = NbtReader.intOf(size.get("y"), 0), sz = NbtReader.intOf(size.get("z"), 0);

                Region reg = new Region();
                reg.ax = Math.abs(sx);
                reg.ay = Math.abs(sy);
                reg.az = Math.abs(sz);
                // a negative size means the region extends backwards from Position
                reg.ox = sx < 0 ? px + sx + 1 : px;
                reg.oy = sy < 0 ? py + sy + 1 : py;
                reg.oz = sz < 0 ? pz + sz + 1 : pz;

                List<Object> pal = NbtReader.list(r.get("BlockStatePalette"));
                if (pal == null || pal.isEmpty()) {
                    throw new IOException("Litematic region '" + e.getKey() + "' has no block palette");
                }
                reg.palette = new State[pal.size()];
                for (int i = 0; i < reg.palette.length; i++) {
                    reg.palette[i] = stateFromCompound(pal.get(i));
                }
                reg.bits = (long[]) r.get("BlockStates");
                reg.bitsPerEntry = Math.max(2, 32 - Integer.numberOfLeadingZeros(reg.palette.length - 1));

                long total = (long) reg.ax * reg.ay * reg.az;
                if ((long) reg.bits.length * 64L < total * reg.bitsPerEntry) {
                    throw new IOException("Litematic region '" + e.getKey() + "' block data is truncated");
                }
                regions.add(reg);
            }
            if (regions.isEmpty()) {
                throw new IOException("Litematic file has no regions");
            }
        }

        @Override
        public String format() {
            return "Litematica";
        }

        @Override
        public void scan(Emit emit) throws IOException {
            for (Region r : regions) {
                long total = (long) r.ax * r.ay * r.az;
                long mask = (1L << r.bitsPerEntry) - 1L;
                int layer = r.ax * r.az;
                for (long idx = 0; idx < total; idx++) {
                    int v = (int) read(r.bits, idx, r.bitsPerEntry, mask);
                    if (v >= r.palette.length) {
                        continue;
                    }
                    State s = r.palette[v];
                    if (s.air) {
                        continue;
                    }
                    int i = (int) idx;
                    int y = i / layer;
                    int rem = i - y * layer;
                    int z = rem / r.ax;
                    int x = rem - z * r.ax;
                    emit.block(r.ox + x, r.oy + y, r.oz + z, s);
                }
            }
        }

        /** Litematica's packed bit array: entries are packed back-to-back and may straddle two longs. */
        private static long read(long[] arr, long index, int bits, long mask) {
            long startOffset = index * bits;
            int startArr = (int) (startOffset >> 6);
            int endArr = (int) (((index + 1) * bits - 1) >> 6);
            int startBit = (int) (startOffset & 63L);
            if (startArr == endArr) {
                return (arr[startArr] >>> startBit) & mask;
            }
            int endOffset = 64 - startBit;
            return ((arr[startArr] >>> startBit) | (arr[endArr] << endOffset)) & mask;
        }
    }

    // ------------------------------------------------------------------------------------
    // Sponge (.schem) v1 / v2 / v3
    // ------------------------------------------------------------------------------------

    private static final class SpongeSource implements Source {
        private final int w, h, l;
        private final State[] palette;
        private final byte[] data;

        SpongeSource(Map<String, Object> s) throws IOException {
            w = NbtReader.intOf(s.get("Width"), 0) & 0xFFFF;
            h = NbtReader.intOf(s.get("Height"), 0) & 0xFFFF;
            l = NbtReader.intOf(s.get("Length"), 0) & 0xFFFF;

            Map<String, Object> palTag;
            Object dataTag;
            Map<String, Object> blocks = NbtReader.compound(s.get("Blocks")); // v3
            if (blocks != null) {
                palTag = NbtReader.compound(blocks.get("Palette"));
                dataTag = blocks.get("Data");
            } else {                                                          // v1 / v2
                palTag = NbtReader.compound(s.get("Palette"));
                dataTag = s.get("BlockData");
            }
            if (palTag == null || !(dataTag instanceof byte[])) {
                throw new IOException("Sponge schematic is missing its palette or block data");
            }
            data = (byte[]) dataTag;

            int max = -1;
            for (Object v : palTag.values()) {
                max = Math.max(max, NbtReader.intOf(v, -1));
            }
            palette = new State[max + 1];
            for (Map.Entry<String, Object> e : palTag.entrySet()) {
                int idx = NbtReader.intOf(e.getValue(), -1);
                if (idx >= 0) {
                    palette[idx] = stateFromString(e.getKey());
                }
            }
        }

        @Override
        public String format() {
            return "Sponge";
        }

        @Override
        public void scan(Emit emit) throws IOException {
            int pos = 0;
            long total = (long) w * h * l;
            for (long i = 0; i < total; i++) {
                // var-int
                int value = 0, shift = 0, b;
                do {
                    if (pos >= data.length) {
                        throw new IOException("Sponge schematic block data is truncated");
                    }
                    b = data[pos++];
                    value |= (b & 0x7F) << shift;
                    shift += 7;
                } while ((b & 0x80) != 0);

                if (value < 0 || value >= palette.length || palette[value] == null || palette[value].air) {
                    continue;
                }
                int idx = (int) i;
                int y = idx / (w * l);
                int rem = idx - y * w * l;
                int z = rem / w;
                int x = rem - z * w;
                emit.block(x, y, z, palette[value]);
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // vanilla structure (.nbt)
    // ------------------------------------------------------------------------------------

    private static final class StructureSource implements Source {
        private final State[] palette;
        private final List<Object> blocks;

        StructureSource(Map<String, Object> root) throws IOException {
            List<Object> pal = NbtReader.list(root.get("palette"));
            if (pal == null) {
                List<Object> palettes = NbtReader.list(root.get("palettes"));
                if (palettes != null && !palettes.isEmpty()) {
                    pal = NbtReader.list(palettes.get(0));
                }
            }
            blocks = NbtReader.list(root.get("blocks"));
            if (pal == null || blocks == null) {
                throw new IOException("Structure file is missing its palette or blocks");
            }
            palette = new State[pal.size()];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = stateFromCompound(pal.get(i));
            }
        }

        @Override
        public String format() {
            return "Structure NBT";
        }

        @Override
        public void scan(Emit emit) throws IOException {
            for (Object o : blocks) {
                Map<String, Object> b = NbtReader.compound(o);
                List<Object> p = b == null ? null : NbtReader.list(b.get("pos"));
                if (p == null || p.size() < 3) {
                    continue;
                }
                int st = NbtReader.intOf(b.get("state"), -1);
                if (st < 0 || st >= palette.length || palette[st].air) {
                    continue;
                }
                emit.block(NbtReader.intOf(p.get(0), 0), NbtReader.intOf(p.get(1), 0), NbtReader.intOf(p.get(2), 0), palette[st]);
            }
        }
    }
}
