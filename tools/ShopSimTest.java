import com.farmbuilder.ext.shop.*;

import java.util.*;

/**
 * Drives ShopBrain against simulated servers with different /shop layouts and checks the outcome.
 * Run with:  python3 tools/run_shop_tests.py
 */
public class ShopSimTest {

    // ===================================================================================
    // simulated server
    // ===================================================================================
    static final class Product {
        final String id, display;
        final double price;
        Product(String id, String display, double price) { this.id = id; this.display = display; this.price = price; }
    }

    static final class FakeShop {
        enum Style { DIRECT, DETAIL }

        // behaviour switches
        Style style = Style.DIRECT;
        boolean shiftBuysStack = true;
        boolean flat = false;
        boolean balReplies = true;
        boolean closeOnBuy = false;
        int invLimit = Integer.MAX_VALUE;
        int latency = 0;

        // server state
        double money;
        final Map<String, Integer> inv = new HashMap<>();
        final List<String> chat = new ArrayList<>();
        int windowId = 0;                      // 0 = closed
        String title = "";
        String view = "";                      // root | cat | detail | flat
        String cat = "";
        int page = 0;
        Product detailProduct;
        int detailQty = 1;
        boolean soldClicked = false;
        int commands = 0, clicks = 0;

        final LinkedHashMap<String, List<Product>> categories = new LinkedHashMap<>();
        final List<Product> flatList = new ArrayList<>();
        final String[] catIcons = {"grass_block", "redstone", "wheat_seeds", "bread", "iron_pickaxe"};
        final Deque<Runnable> pending = new ArrayDeque<>();
        final Deque<Integer> due = new ArrayDeque<>();

        FakeShop(double money) {
            this.money = money;
            List<Product> blocks = new ArrayList<>();
            String[] ids = {"stone", "cobblestone", "oak_planks", "spruce_planks", "birch_planks", "glass", "sand", "gravel",
                    "dirt", "grass_block", "oak_log", "spruce_log", "bricks", "stone_bricks", "sandstone", "netherrack",
                    "end_stone", "prismarine", "quartz_block", "terracotta", "white_wool", "red_wool", "blue_wool",
                    "obsidian", "glowstone", "sea_lantern", "bookshelf", "tnt_decor", "clay", "mud"};
            for (String id : ids) blocks.add(new Product(id, title(id), id.equals("obsidian") ? 50 : 2));
            categories.put("Blocks", blocks);
            categories.put("Redstone", List.of(new Product("redstone", "Redstone Dust", 5), new Product("piston", "Piston", 40),
                    new Product("observer", "Observer", 30), new Product("hopper", "Hopper", 25), new Product("repeater", "Repeater", 10)));
            categories.put("Farming", List.of(new Product("wheat_seeds", "Wheat Seeds", 1), new Product("carrot", "Carrot", 2),
                    new Product("potato", "Potato", 2), new Product("sugar_cane", "Sugar Cane", 3)));
            categories.put("Food", List.of(new Product("bread", "Bread", 4), new Product("cooked_beef", "Steak", 6)));
            categories.put("Tools", List.of(new Product("iron_pickaxe", "Iron Pickaxe", 100)));
            for (Product p : blocks) flatList.add(p);
        }

        static String title(String id) {
            StringBuilder sb = new StringBuilder();
            for (String w : id.split("_")) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            return sb.toString().trim();
        }

        static String fmt(double v) { return String.format("%,.2f", v).replace(".00", ""); }

        int count(String id) { return inv.getOrDefault(id, 0); }

        // ---- time / latency ----
        void schedule(int now, Runnable r) { pending.add(r); due.add(now + latency); }

        void advance(int now) {
            while (!due.isEmpty() && due.peekFirst() <= now) { due.pollFirst(); pending.pollFirst().run(); }
        }

        // ---- actions ----
        void command(String c) {
            commands++;
            if (c.equals("shop")) {
                windowId++;
                if (flat) { view = "flat"; page = 0; title = "Shop"; } else { view = "root"; title = "Shop"; }
            } else if (c.equals("bal") && balReplies) {
                chat.add("§aYour balance: §f$" + fmt(money));
            }
        }

        void close() { windowId = 0; view = ""; }

        void click(int slot, boolean shift) {
            clicks++;
            if (windowId == 0) return;
            switch (view) {
                case "root": {
                    List<String> names = new ArrayList<>(categories.keySet());
                    int idx = slot - 11;
                    if (idx >= 0 && idx < names.size()) { cat = names.get(idx); page = 0; view = "cat"; windowId++; title = "Shop - " + cat; }
                    else if (slot == 40) soldClicked = true;
                    else if (slot == 49) close();
                    break;
                }
                case "cat": case "flat": {
                    List<Product> list = view.equals("flat") ? flatList : categories.get(cat);
                    int per = 21;
                    if (slot == 53 && (page + 1) * per < list.size()) { page++; break; }       // next page, same window
                    if (slot == 45) { if (page > 0) page--; else if (view.equals("cat")) { view = "root"; windowId++; title = "Shop"; } break; }
                    if (slot == 49) { close(); break; }
                    int pos = productPos(slot);
                    if (pos < 0) break;
                    int i = page * per + pos;
                    if (i >= list.size()) break;
                    Product p = list.get(i);
                    if (style == Style.DIRECT) {
                        int n = (shift && shiftBuysStack) ? 64 : 1;
                        int total = 0; for (int v : inv.values()) total += v;
                        if (total + n > invLimit) chat.add("§cYour inventory is full!");
                        else if (money >= p.price * n) { money -= p.price * n; inv.merge(p.id, n, Integer::sum); if (closeOnBuy) close(); }
                        else chat.add("§cYou don't have enough money!");
                    } else {
                        detailProduct = p; detailQty = 1; view = "detail"; windowId++; title = "Buy " + p.display;
                    }
                    break;
                }
                case "detail": {
                    if (slot == 20) detailQty += 1; else if (slot == 21) detailQty += 10; else if (slot == 22) detailQty += 64;
                    else if (slot == 18) detailQty = Math.max(1, detailQty - 1);
                    else if (slot == 31) {
                        double cost = detailProduct.price * detailQty;
                        if (money >= cost) { money -= cost; inv.merge(detailProduct.id, detailQty, Integer::sum); }
                        else chat.add("§cNot enough money to buy that.");
                        view = flat ? "flat" : "cat"; windowId++;
                    } else if (slot == 33) { view = flat ? "flat" : "cat"; windowId++; }
                    break;
                }
                default: break;
            }
        }

        static final int[] POS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        static int productPos(int slot) { for (int i = 0; i < POS.length; i++) if (POS[i] == slot) return i; return -1; }

        // ---- what the client sees ----
        ShopScreen snapshot() {
            if (windowId == 0) return null;
            List<ShopSlot> s = new ArrayList<>();
            switch (view) {
                case "root": {
                    int i = 0;
                    for (String c : categories.keySet()) {
                        s.add(new ShopSlot(11 + i, catIcons[i], "§a" + c, List.of("§7Click to browse " + c), 1)); i++;
                    }
                    s.add(new ShopSlot(40, "chest", "§cSell Items", List.of("§7Sell everything in your inventory"), 1));
                    s.add(new ShopSlot(49, "barrier", "§cClose", List.of(), 1));
                    s.add(new ShopSlot(4, "gold_ingot", "§6Your Balance", List.of("§7$" + fmt(money)), 1));
                    for (int f : new int[]{0, 1, 2, 3, 5, 6, 7, 8}) s.add(new ShopSlot(f, "gray_stained_glass_pane", " ", List.of(), 1));
                    break;
                }
                case "cat": case "flat": {
                    List<Product> list = view.equals("flat") ? flatList : categories.get(cat);
                    int per = 21;
                    for (int k = 0; k < per; k++) {
                        int i = page * per + k;
                        if (i >= list.size()) break;
                        Product p = list.get(i);
                        s.add(new ShopSlot(POS[k], p.id, "§f" + p.display, List.of("§7Price: §a$" + fmt(p.price) + " each", "§eClick to buy"), 1));
                    }
                    if ((page + 1) * per < list.size()) s.add(new ShopSlot(53, "arrow", "§eNext Page §7(" + (page + 2) + ")", List.of(), 1));
                    s.add(new ShopSlot(45, "arrow", "§eBack", List.of(), 1));
                    s.add(new ShopSlot(49, "barrier", "§cClose", List.of(), 1));
                    s.add(new ShopSlot(4, "gold_ingot", "§6Your Balance", List.of("§7$" + fmt(money)), 1));
                    break;
                }
                case "detail": {
                    s.add(new ShopSlot(13, detailProduct.id, "§f" + detailProduct.display, List.of("§7Total: $" + fmt(detailProduct.price * detailQty)), detailQty));
                    s.add(new ShopSlot(18, "red_stained_glass_pane", "§c-1", List.of(), 1));
                    s.add(new ShopSlot(20, "lime_stained_glass_pane", "§a+1", List.of(), 1));
                    s.add(new ShopSlot(21, "lime_stained_glass_pane", "§a+10", List.of(), 10));
                    s.add(new ShopSlot(22, "lime_stained_glass_pane", "§a+64", List.of(), 64));
                    s.add(new ShopSlot(31, "lime_stained_glass_pane", "§aConfirm", List.of(), 1));
                    s.add(new ShopSlot(33, "red_stained_glass_pane", "§cCancel", List.of(), 1));
                    break;
                }
                default: break;
            }
            return new ShopScreen(windowId, title, s);
        }
    }

    // ===================================================================================
    // driver
    // ===================================================================================
    static final class Outcome {
        boolean finished, ok; String message; int ticks;
    }

    static Outcome drive(FakeShop shop, ShopBrain brain, String trackedItem) {
        Outcome o = new Outcome();
        for (int t = 0; t < 9000; t++) {
            shop.advance(t);
            for (String m : new ArrayList<>(shop.chat)) brain.onChat(m);
            shop.chat.clear();
            ShopAction a = brain.next(shop.snapshot(), shop.count(trackedItem));
            final int now = t;
            switch (a.type) {
                case COMMAND: { String c = a.text; shop.schedule(now, () -> shop.command(c)); break; }
                case CLICK: { int sl = a.slot; boolean sh = a.shift; shop.schedule(now, () -> shop.click(sl, sh)); break; }
                case CLOSE: shop.schedule(now, shop::close); break;
                default: break;
            }
            if (brain.isFinished()) {
                for (int k = 0; k < 6; k++) { shop.advance(t + k + 1); }
                o.finished = true; o.ok = brain.isOk(); o.message = brain.message(); o.ticks = t;
                return o;
            }
            for (String m : new ArrayList<>(shop.chat)) { brain.onChat(m); }
            shop.chat.clear();
        }
        o.message = "never finished";
        return o;
    }

    static ShopSettings settings() { ShopSettings s = new ShopSettings(); return s; }

    // ===================================================================================
    // tests
    // ===================================================================================
    static int failures = 0, passes = 0;

    static void check(String name, boolean cond, String detail) {
        if (cond) { passes++; System.out.println("ok    " + name); }
        else { failures++; System.out.println("FAIL  " + name + "   -> " + detail); }
    }

    static ShopBrain buy(String item, int missing, int have, ShopCatalog cat) {
        return new ShopBrain(ShopBrain.Mode.BUY, item, missing, have, settings(), cat, null);
    }

    public static void main(String[] args) {
        // ---- A: category shop, item on page 2 of Blocks, shift buys a stack -------------------------------------
        for (int lat : new int[]{0, 1, 3}) {
            FakeShop s = new FakeShop(100000); s.latency = lat;
            Outcome o = drive(s, buy("obsidian", 200, 0, null), "obsidian");
            check("A obsidian x200 (page 2, shift=stack, latency " + lat + ")", o.ok && s.count("obsidian") == 200 && !s.soldClicked,
                    o.message + " have=" + s.count("obsidian") + " sold=" + s.soldClicked);
            if (lat == 0) check("A money spent is exactly 200 x $50", Math.abs((100000 - s.money) - 10000) < 0.01, "spent " + (100000 - s.money));
        }

        // ---- B: shift-click does NOT buy a stack: must not break, must not overbuy -------------------------
        { FakeShop s = new FakeShop(100000); s.shiftBuysStack = false;
          Outcome o = drive(s, buy("cobblestone", 100, 0, null), "cobblestone");
          check("B cobblestone x100 (shift is just +1)", o.ok && s.count("cobblestone") == 100, o.message + " have=" + s.count("cobblestone")); }

        // ---- C/D: other categories --------------------------------------------------------------------------
        { FakeShop s = new FakeShop(5000); Outcome o = drive(s, buy("piston", 3, 0, null), "piston");
          check("C piston x3 (Redstone category)", o.ok && s.count("piston") == 3 && !s.soldClicked, o.message + " have=" + s.count("piston"));
          check("C went straight to Redstone (few /shop commands)", s.commands <= 3, "commands=" + s.commands); }
        { FakeShop s = new FakeShop(5000); Outcome o = drive(s, buy("wheat_seeds", 128, 0, null), "wheat_seeds");
          check("D wheat_seeds x128 (Farming category)", o.ok && s.count("wheat_seeds") == 128, o.message + " have=" + s.count("wheat_seeds")); }

        // ---- already holds some: only the missing part is bought ---------------------------------------------
        { FakeShop s = new FakeShop(5000); s.inv.put("glass", 40);
          Outcome o = drive(s, buy("glass", 24, 40, null), "glass");
          check("D2 has 40 glass, needs 24 more -> ends with 64", o.ok && s.count("glass") == 64, o.message + " have=" + s.count("glass")); }

        // ---- E: not sold here --------------------------------------------------------------------------------
        { FakeShop s = new FakeShop(100000); Outcome o = drive(s, buy("elytra", 1, 0, null), "elytra");
          check("E elytra: reports not found, buys nothing, never presses Sell", o.finished && !o.ok && o.message.contains("not found")
                  && s.money == 100000 && !s.soldClicked, o.message + " sold=" + s.soldClicked + " money=" + s.money); }

        // ---- F/G/L: no money --------------------------------------------------------------------------------
        { FakeShop s = new FakeShop(10); Outcome o = drive(s, buy("obsidian", 64, 0, null), "obsidian");
          check("F no money (balance known): refuses before buying anything", o.finished && !o.ok && s.count("obsidian") == 0 && s.money == 10
                  && o.message.toLowerCase().contains("not enough money"), o.message); }
        { FakeShop s = new FakeShop(10); s.balReplies = false; Outcome o = drive(s, buy("obsidian", 64, 0, null), "obsidian");
          check("G no money (server never answers /bal): server refusal stops it", o.finished && !o.ok && s.count("obsidian") == 0 && s.money == 10, o.message); }
        { FakeShop s = new FakeShop(3000); Outcome o = drive(s, buy("obsidian", 64, 0, null), "obsidian");   // 64 x 50 = 3200 > 3000
          check("L can afford only part (3000 < 3200): buys nothing at all", o.finished && !o.ok && s.count("obsidian") == 0 && s.money == 3000, o.message); }
        { FakeShop s = new FakeShop(3200); Outcome o = drive(s, buy("obsidian", 64, 0, null), "obsidian");   // exactly enough
          check("L2 exactly enough money: buys it all", o.ok && s.count("obsidian") == 64 && s.money == 0, o.message + " money=" + s.money); }
        { FakeShop s = new FakeShop(100000); ShopSettings st = settings(); st.maxSpend = 500;
          Outcome o = drive(s, new ShopBrain(ShopBrain.Mode.BUY, "obsidian", 64, 0, st, null, null), "obsidian");
          check("M spend cap 500 blocks a $3200 purchase", o.finished && !o.ok && s.count("obsidian") == 0, o.message); }

        // ---- H: quantity + confirm screen ---------------------------------------------------------------------
        { FakeShop s = new FakeShop(100000); s.style = FakeShop.Style.DETAIL;
          Outcome o = drive(s, buy("cobblestone", 100, 0, null), "cobblestone");
          check("H detail screen: +64/+10/+1 then Confirm -> exactly 100", o.ok && s.count("cobblestone") == 100, o.message + " have=" + s.count("cobblestone")); }
        { FakeShop s = new FakeShop(50); s.style = FakeShop.Style.DETAIL; s.balReplies = false;
          Outcome o = drive(s, buy("cobblestone", 100, 0, null), "cobblestone");
          check("H2 detail screen without money: server says no -> nothing bought", o.finished && !o.ok && s.count("cobblestone") == 0, o.message); }

        // ---- I: flat shop, no categories, paged -----------------------------------------------------------------
        { FakeShop s = new FakeShop(5000); s.flat = true; Outcome o = drive(s, buy("mud", 10, 0, null), "mud");
          check("I flat paged shop (no categories): finds 'mud' on page 2", o.ok && s.count("mud") == 10, o.message); }

        // ---- J: scan the whole shop, then buy using the saved route -------------------------------------------------
        { FakeShop s = new FakeShop(5000); ShopCatalog cat = new ShopCatalog();
          Outcome o = drive(s, new ShopBrain(ShopBrain.Mode.SCAN, null, 0, 0, settings(), cat, null), "x");
          int expected = 30 + 5 + 4 + 2 + 1;
          check("J scan visits every category and page, records all " + expected + " items", o.ok && cat.items.size() == expected,
                  o.message + " items=" + cat.items.size());
          check("J scan never pressed 'Sell Items'", !s.soldClicked, "sold clicked");
          check("J scan found obsidian with price $50", cat.get("obsidian") != null && cat.get("obsidian").price == 50, String.valueOf(cat.get("obsidian")));
          s.money = 100000; s.commands = 0;
          Outcome b = drive(s, buy("obsidian", 5, 0, cat), "obsidian");
          check("J2 buy with catalog: goes straight there with one /shop", b.ok && s.count("obsidian") == 5 && s.commands <= 2, b.message + " commands=" + s.commands); }

        // ---- O: shop that closes its menu after every purchase --------------------------------------------------------
        { FakeShop s = new FakeShop(100000); s.closeOnBuy = true; s.shiftBuysStack = false;
          Outcome o = drive(s, buy("piston", 12, 0, null), "piston");
          check("O menu closes after each buy: reopens and keeps going until 12", o.ok && s.count("piston") == 12, o.message + " have=" + s.count("piston")); }
        { FakeShop s = new FakeShop(100000); s.invLimit = 5; s.shiftBuysStack = false;
          s.chat.clear(); Outcome o = drive(s, buy("cobblestone", 20, 0, null), "cobblestone");
          check("P server says inventory full: stops instead of looping", o.finished && !o.ok && s.count("cobblestone") == 5, o.message + " have=" + s.count("cobblestone")); }

        // ---- N: the unexpected: shop opens a different menu when stale route replays ---------------------------------
        { FakeShop s = new FakeShop(100000); ShopCatalog cat = new ShopCatalog();
          cat.putIfAbsent("obsidian", "Obsidian", 50, List.of("grass_block|nonexistent", "<NEXT>"));      // wrong route
          Outcome o = drive(s, buy("obsidian", 4, 0, cat), "obsidian");
          check("N stale catalog route falls back to exploring", o.ok && s.count("obsidian") == 4, o.message); }

        // ---- money parsing -------------------------------------------------------------------------------------------------
        double[][] none = {};
        check("money 1,234.50", Money.parse("Balance: $1,234.50") == 1234.5, String.valueOf(Money.parse("Balance: $1,234.50")));
        check("money 1.234,50 (EU)", Money.parse("1.234,50") == 1234.5, String.valueOf(Money.parse("1.234,50")));
        check("money 1.5k", Money.parse("$1.5k") == 1500, String.valueOf(Money.parse("$1.5k")));
        check("money 2.25M", Money.parse("2.25M coins") == 2_250_000, String.valueOf(Money.parse("2.25M coins")));
        check("money 1,5 (decimal comma)", Money.parse("1,5") == 1.5, String.valueOf(Money.parse("1,5")));
        check("money '10 kills' is not 10k", Money.parse("10 kills") == 10, String.valueOf(Money.parse("10 kills")));
        check("price skips the sell line", Money.findPrice(List.of("§7Sell: §c$2", "§7Buy: §a$10")) == 10, String.valueOf(Money.findPrice(List.of("§7Sell: §c$2", "§7Buy: §a$10"))));
        check("price ignores 'buy 64'", Double.isNaN(Money.findPrice(List.of("§eClick to buy 64"))), "got a price");
        check("unit price: 'per stack' $640 -> $10 each", Money.unitPrice(List.of("§7Price: $640 per stack"), 1) == 10, String.valueOf(Money.unitPrice(List.of("§7Price: $640 per stack"), 1)));
        check("unit price: '$5 each'", Money.unitPrice(List.of("§7Price: $5 each"), 1) == 5, "x");

        System.out.println();
        System.out.println(failures == 0 ? "ALL " + passes + " PASSED" : failures + " FAILED, " + passes + " passed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
