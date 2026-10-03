package com.farmbuilder.ext.schem;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimal NBT reader with no Minecraft dependency.
 *
 * Value mapping: TAG_Byte->Byte, Short->Short, Int->Integer, Long->Long, Float->Float,
 * Double->Double, ByteArray->byte[], String->String, List->List&lt;Object&gt;,
 * Compound->Map&lt;String,Object&gt;, IntArray->int[], LongArray->long[].
 */
public final class NbtReader {
    private static final int MAX_DEPTH = 512;
    private static final int MAX_ARRAY = 1 << 29; // ~536M entries, far above any real schematic

    private NbtReader() {
    }

    /** Reads the root compound of a (gzip-compressed or raw) NBT file. */
    public static Map<String, Object> read(Path file) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            raw.mark(2);
            int b0 = raw.read();
            int b1 = raw.read();
            raw.reset();
            InputStream in = (b0 == 0x1f && b1 == 0x8b) ? new GZIPInputStream(raw, 1 << 16) : raw;
            DataInputStream din = new DataInputStream(new BufferedInputStream(in, 1 << 16));
            int type = din.readUnsignedByte();
            if (type != 10) {
                throw new IOException("Not an NBT file (root tag is not a compound)");
            }
            din.readUTF(); // root name (usually empty)
            return readCompound(din, 0);
        }
    }

    private static Map<String, Object> readCompound(DataInputStream in, int depth) throws IOException {
        checkDepth(depth);
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) {
                return map;
            }
            String name = in.readUTF();
            map.put(name, readPayload(in, type, depth + 1));
        }
    }

    private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
        switch (type) {
            case 1:
                return in.readByte();
            case 2:
                return in.readShort();
            case 3:
                return in.readInt();
            case 4:
                return in.readLong();
            case 5:
                return in.readFloat();
            case 6:
                return in.readDouble();
            case 7: {
                int len = checkLen(in.readInt());
                byte[] a = new byte[len];
                in.readFully(a);
                return a;
            }
            case 8:
                return in.readUTF();
            case 9: {
                checkDepth(depth);
                int elemType = in.readUnsignedByte();
                int len = checkLen(in.readInt());
                if (elemType == 0 && len > 0) {
                    throw new IOException("Corrupt NBT list (type END with length " + len + ")");
                }
                List<Object> list = new ArrayList<>(Math.min(len, 1 << 16));
                for (int i = 0; i < len; i++) {
                    list.add(readPayload(in, elemType, depth + 1));
                }
                return list;
            }
            case 10:
                return readCompound(in, depth);
            case 11: {
                int len = checkLen(in.readInt());
                int[] a = new int[len];
                for (int i = 0; i < len; i++) {
                    a[i] = in.readInt();
                }
                return a;
            }
            case 12: {
                int len = checkLen(in.readInt());
                long[] a = new long[len];
                for (int i = 0; i < len; i++) {
                    a[i] = in.readLong();
                }
                return a;
            }
            default:
                throw new IOException("Unknown NBT tag type " + type);
        }
    }

    private static void checkDepth(int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nesting too deep");
        }
    }

    private static int checkLen(int len) throws IOException {
        if (len < 0 || len > MAX_ARRAY) {
            throw new IOException("Invalid NBT array length " + len);
        }
        return len;
    }

    // ---- small typed accessors used by the converter ------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static int intOf(Object o, int fallback) {
        return o instanceof Number ? ((Number) o).intValue() : fallback;
    }

    public static String stringOf(Object o, String fallback) {
        return o instanceof String ? (String) o : fallback;
    }
}
