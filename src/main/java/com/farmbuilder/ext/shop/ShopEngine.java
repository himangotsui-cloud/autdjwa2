package com.farmbuilder.ext.shop;

import com.farmbuilder.ext.ExtConfig;
import com.farmbuilder.ext.LegacyBridge;
import com.farmbuilder.ext.SchematicImporter;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs a {@link ShopBrain} inside the game: every client tick it snapshots the open chest-style menu,
 * asks the brain what to do, and sends the command / slot click.
 *
 * While a job is running {@link #isBusy()} is true; the original builder sees that through the
 * AutoOrderEngine.isBusy() mixin and simply waits.
 */
public final class ShopEngine {
    private static ShopBrain brain;
    private static String item;                       // null while scanning
    private static ShopCatalog catalog;
    private static int catalogSizeAtStart;
    private static int emptyScreenTicks;
    private static PrintWriter scanLog;
    private static final Map<String, Long> COOLDOWN = new HashMap<>();
    private static volatile boolean stopRequested;
    private static int consecutiveFailures;
    private static int menusReported;

    private ShopEngine() {
    }

    public static boolean isBusy() {
        ShopBrain b = brain;
        return b != null && !b.isFinished();
    }

    // =========================================================================================
    // entry points
    // =========================================================================================

    /**
     * Called instead of /order. {@code required} is the total wanted, {@code have} what the player holds.
     */
    public static void request(String rawItem, int required, int have) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c == null || c.player == null || isBusy()) {
            return;
        }
        String id = normalize(rawItem);
        int missing = required - have;
        if (id.isEmpty() || missing <= 0) {
            return;
        }
        Long until = COOLDOWN.get(id);
        if (until != null && System.currentTimeMillis() < until) {
            return;                                    // failed recently - don't spam the shop
        }
        item = id;
        start(c, new ShopBrain(ShopBrain.Mode.BUY, id, missing, count(c, id), settings(), loadCatalog(c), listener()));
        say("§6[AutoShop] §f" + LegacyBridge.tr("Cần thêm §e", "Need §e") + missing + "x " + id
                + LegacyBridge.tr(" §f-> vào /", " §f-> opening /") + ExtConfig.shopCommand + "...");
    }

    /** Walks the whole shop and remembers every item, price and where it is. */
    public static void startScan() {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c == null || c.player == null) {
            return;
        }
        if (isBusy()) {
            say("§e[AutoShop] " + LegacyBridge.tr("Đang bận, gõ /farmshop stop để dừng.", "Busy - /farmshop stop to cancel."));
            return;
        }
        item = null;
        ShopCatalog fresh = new ShopCatalog();
        fresh.server = serverKey(c);
        catalog = fresh;
        ShopSettings scanCfg = settings();
        scanCfg.maxScreens = 300;
        scanCfg.maxActions = 2000;
        scanCfg.maxTicks = 20 * 600;
        scanCfg.maxOpens = 120;
        start(c, new ShopBrain(ShopBrain.Mode.SCAN, null, 0, 0, scanCfg, fresh, listener()));
        say("§6[AutoShop] §f" + LegacyBridge.tr("Đang quét toàn bộ /", "Scanning the whole /") + ExtConfig.shopCommand + "...");
    }

    /** Manual test: /farmshop buy <item> <count> */
    public static void buyManual(String rawItem, int amount) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c == null || c.player == null) {
            return;
        }
        String id = normalize(rawItem);
        COOLDOWN.remove(id);
        int have = count(c, id);
        request(id, have + amount, have);
    }

    /** Stops any running job and turns Shop mode OFF so the builder cannot start it again by itself. */
    public static void stop() {
        boolean wasBusy = isBusy();
        if (wasBusy) {
            finish(MinecraftClient.getInstance(), false, LegacyBridge.tr("Đã dừng.", "Stopped."), false);
        }
        if (ExtConfig.shopEnabled) {
            ExtConfig.shopEnabled = false;
            ExtConfig.save();
            say("§e[AutoShop] " + LegacyBridge.tr("Đã TẮT chế độ Shop. Bật lại bằng menu hoặc /farmshop on.",
                    "Shop mode switched OFF. Turn it back on in the menu or with /farmshop on."));
        }
    }

    /** Safe to call from the key-press callback; the next tick performs the stop. */
    public static void requestStop() {
        stopRequested = true;
    }

    public static String statusLine() {
        ShopBrain b = brain;
        if (b == null || b.isFinished()) {
            return "idle";
        }
        return (item == null ? "scanning" : "buying " + item) + ", menus seen " + b.screensSeen();
    }

    public static ShopCatalog catalogOrNull() {
        return catalog;
    }

    // =========================================================================================
    // per-tick / chat
    // =========================================================================================

    public static void tick(MinecraftClient c) {
        if (stopRequested) {
            stopRequested = false;
            if (isBusy()) {
                stop();
                return;
            }
        }
        ShopBrain b = brain;
        if (b == null || b.isFinished()) {
            return;
        }
        if (c.player == null || c.world == null || c.getNetworkHandler() == null) {
            finish(c, false, "Left the world.", false);
            return;
        }
        ShopScreen scr = snapshot(c);
        if (scr != null && scr.slots.isEmpty() && ++emptyScreenTicks < 40) {
            return;                                    // the server has opened the menu but not sent the items yet
        }
        if (scr == null || !scr.slots.isEmpty()) {
            emptyScreenTicks = 0;
        }

        ShopAction a = b.next(scr, item == null ? 0 : count(c, item));
        switch (a.type) {
            case COMMAND:
                c.getNetworkHandler().sendChatCommand(a.text);
                break;
            case CLICK:
                if (c.player.currentScreenHandler instanceof GenericContainerScreenHandler h
                        && a.slot >= 0 && a.slot < h.slots.size()) {
                    c.interactionManager.clickSlot(h.syncId, a.slot, 0,
                            a.shift ? SlotActionType.QUICK_MOVE : SlotActionType.PICKUP, c.player);
                }
                break;
            case CLOSE:
                if (c.currentScreen != null) {
                    c.player.closeHandledScreen();
                }
                break;
            default:
                break;
        }
        if (b.isFinished()) {
            finish(c, b.isOk(), b.message(), true);
        }
    }

    public static void onChat(String message) {
        ShopBrain b = brain;
        if (b != null && !b.isFinished()) {
            b.onChat(message);
        }
    }

    // =========================================================================================
    // internals
    // =========================================================================================

    private static void start(MinecraftClient c, ShopBrain b) {
        brain = b;
        emptyScreenTicks = 0;
        menusReported = 0;
        stopRequested = false;
        catalogSizeAtStart = catalog == null ? 0 : catalog.items.size();
        openScanLog(b);
    }

    private static void finish(MinecraftClient c, boolean ok, String msg, boolean fromBrain) {
        ShopBrain b = brain;
        String id = item;
        brain = null;
        closeScanLog();
        if (c != null && c.player != null && c.currentScreen != null && !fromBrain) {
            c.player.closeHandledScreen();
        }
        if (catalog != null && catalog.items.size() > catalogSizeAtStart) {
            try {
                catalog.save(catalogFile());
            } catch (IOException e) {
                System.err.println("[FarmBuilder] Could not save shop catalog: " + e.getMessage());
            }
        }
        boolean failedByBrain = id != null && !ok && fromBrain;
        if (ok) {
            consecutiveFailures = 0;
            if (id != null) COOLDOWN.remove(id);
        } else if (failedByBrain) {
            COOLDOWN.put(id, System.currentTimeMillis() + ExtConfig.shopRetryCooldownSec * 1000L);
            consecutiveFailures++;
        }
        say((ok ? "§a[AutoShop] §f" : "§c[AutoShop] §f") + msg
                + (failedByBrain ? "§7 (" + LegacyBridge.tr("thử lại sau ", "will retry in ") + ExtConfig.shopRetryCooldownSec + "s)" : ""));
        if (failedByBrain && consecutiveFailures >= Math.max(1, ExtConfig.shopMaxFailures)) {
            consecutiveFailures = 0;
            ExtConfig.shopEnabled = false;
            ExtConfig.save();
            say("§c[AutoShop] " + LegacyBridge.tr("Thất bại nhiều lần liên tiếp -> TỰ TẮT chế độ Shop. Gửi file logs/farmshop-scan.txt để chỉnh cho server của bạn.",
                    "Failed several times in a row -> Shop mode switched itself OFF. Send logs/farmshop-scan.txt so it can be tuned for your server."));
        }
    }

    private static ShopSettings settings() {
        ShopSettings s = new ShopSettings();
        s.shopCommand = ExtConfig.shopCommand;
        s.balanceCommand = ExtConfig.balanceCommand;
        s.checkBalance = ExtConfig.shopCheckBalance;
        s.maxSpend = ExtConfig.shopMaxSpend;
        return s;
    }

    private static ShopCatalog loadCatalog(MinecraftClient c) {
        if (catalog == null) {
            ShopCatalog loaded = ShopCatalog.load(catalogFile());
            catalog = loaded.server.equals(serverKey(c)) ? loaded : new ShopCatalog();
            catalog.server = serverKey(c);
        }
        return catalog;
    }

    private static Path catalogFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("farmbuilder-shop-catalog.tsv");
    }

    private static String serverKey(MinecraftClient c) {
        return c.getCurrentServerEntry() != null ? c.getCurrentServerEntry().address : "singleplayer";
    }

    /** Same item aliasing the original order bot used ("water" -> water_bucket). */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toLowerCase(Locale.ROOT).replace("minecraft:", "").trim();
        if (s.contains("water") && !s.contains("bucket")) return "water_bucket";
        if (s.contains("lava") && !s.contains("bucket")) return "lava_bucket";
        return s;
    }

    static int count(MinecraftClient c, String id) {
        if (c.player == null) {
            return 0;
        }
        PlayerInventory inv = c.player.getInventory();
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getStack(i);
            if (!st.isEmpty() && Registries.ITEM.getId(st.getItem()).getPath().equals(id)) {
                n += st.getCount();
            }
        }
        return n;
    }

    /** Reads the open chest-style menu into plain data; null if no such menu is open. */
    static ShopScreen snapshot(MinecraftClient c) {
        if (c.currentScreen == null || c.player == null) {
            return null;
        }
        ScreenHandler sh = c.player.currentScreenHandler;
        if (!(sh instanceof GenericContainerScreenHandler h) || sh.syncId == 0) {
            return null;
        }
        int n = h.getRows() * 9;
        List<ShopSlot> slots = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ItemStack st = h.getSlot(i).getStack();
            if (st.isEmpty()) {
                continue;
            }
            List<String> lore = new ArrayList<>();
            LoreComponent lc = st.get(DataComponentTypes.LORE);
            if (lc != null) {
                for (Text t : lc.lines()) {
                    lore.add(t.getString());
                }
            }
            slots.add(new ShopSlot(i, Registries.ITEM.getId(st.getItem()).getPath(),
                    st.getName().getString(), lore, st.getCount()));
        }
        return new ShopScreen(sh.syncId, c.currentScreen.getTitle().getString(), slots);
    }

    private static void say(String msg) {
        SchematicImporter.say(msg);
    }

    // ---- scan log: every menu the bot looked at, so an odd shop can be diagnosed from one file -----------

    private static ShopBrain.Listener listener() {
        return (route, screen) -> {
            if (menusReported++ < 6) {
                int priced = 0;
                for (ShopSlot x : screen.slots) if (x.isProduct()) priced++;
                say("§7[AutoShop] " + LegacyBridge.tr("Menu \"", "Menu \"") + screen.title + "\": " + screen.slots.size()
                        + " items, " + priced + LegacyBridge.tr(" có giá", " with a price")
                        + (priced == 0 && !route.isEmpty() ? "§e  (no prices recognised here)" : ""));
            }
            if (scanLog == null) {
                return;
            }
            scanLog.println("--- route: " + (route.isEmpty() ? "(root)" : String.join(" > ", route)));
            scanLog.println("    window id=" + screen.id + "  title=\"" + screen.title + "\"  slots=" + screen.slots.size());
            for (ShopSlot s : screen.slots) {
                scanLog.println(String.format("    [%2d] %-28s x%-3d name=\"%s\"%s", s.index, s.itemId, s.count, s.name,
                        s.lore.isEmpty() ? "" : "  lore=" + s.lore));
            }
            scanLog.flush();
        };
    }

    private static void openScanLog(ShopBrain b) {
        closeScanLog();
        if (!ExtConfig.shopDebugLog && item != null) {
            return;
        }
        try {
            Path f = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("farmshop-scan.txt");
            Files.createDirectories(f.getParent());
            scanLog = new PrintWriter(Files.newBufferedWriter(f, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
            scanLog.println("# FarmBuilder shop log - " + (item == null ? "SCAN" : "BUY " + item) + "  (" + new java.util.Date() + ")");
        } catch (IOException e) {
            scanLog = null;
        }
    }

    private static void closeScanLog() {
        if (scanLog != null) {
            scanLog.close();
            scanLog = null;
        }
    }
}
