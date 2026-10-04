package com.farmbuilder.ext.shop;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What the shop sells and how to get to it (item -> price + click route), saved so the next buy is direct. */
public final class ShopCatalog {
    public static final class Entry {
        public final String itemId;
        public final String name;
        public final double price;
        public final List<String> route;

        public Entry(String itemId, String name, double price, List<String> route) {
            this.itemId = itemId;
            this.name = name;
            this.price = price;
            this.route = route;
        }
    }

    private static final String SEP = "\u001f";
    public final Map<String, Entry> items = new LinkedHashMap<>();
    public String server = "";

    public void putIfAbsent(String itemId, String name, double price, List<String> route) {
        items.putIfAbsent(itemId, new Entry(itemId, name, price, new ArrayList<>(route)));
    }

    public Entry get(String itemId) {
        return items.get(itemId);
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("# server=" + server + "\n");
            for (Entry e : items.values()) {
                w.write(e.itemId + "\t" + e.price + "\t" + e.name.replace('\t', ' ') + "\t" + String.join(SEP, e.route) + "\n");
            }
        }
    }

    public static ShopCatalog load(Path file) {
        ShopCatalog c = new ShopCatalog();
        if (!Files.isRegularFile(file)) {
            return c;
        }
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("# server=")) {
                    c.server = line.substring(9);
                    continue;
                }
                String[] p = line.split("\t", -1);
                if (p.length < 4) continue;
                List<String> route = p[3].isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(p[3].split(SEP)));
                double price;
                try {
                    price = Double.parseDouble(p[1]);
                } catch (NumberFormatException e) {
                    price = Double.NaN;
                }
                c.items.put(p[0], new Entry(p[0], p[2], price, route));
            }
        } catch (IOException ignored) {
            // unreadable catalog -> behave as if there is none
        }
        return c;
    }
}
