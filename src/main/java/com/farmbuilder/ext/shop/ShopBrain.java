package com.farmbuilder.ext.shop;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides what to click in a server's /shop menus. Pure logic: the caller (ShopEngine) feeds it a
 * snapshot every tick and performs the action it returns.
 *
 *  BUY  mode: walk the shop (best-guess categories first, then everything), find the item, check the
 *             price against the balance, buy until the inventory holds the wanted amount.
 *  SCAN mode: walk the whole shop and record every product (item, price, route) in the catalog.
 *
 * Menus are identified by routes - the list of button keys clicked from the /shop root - and every
 * route is replayed from a fresh /shop, because slot positions and window ids differ between visits.
 */
public final class ShopBrain {
    public enum Mode { BUY, SCAN }

    public interface Listener {
        void onScreen(List<String> route, ShopScreen screen);
    }

    static final String NEXT = "<NEXT>";
    private static final Pattern QTY_ADD = Pattern.compile("(?i)^\\s*(?:\\+|add\\s*)\\s*(\\d+)");
    private static final Pattern CONFIRM = Pattern.compile(
            "(?i)confirm|purchase|accept|buy now|\\bbuy\\b|\\byes\\b|xác nhận|\\bmua\\b|đồng ý|✔|✓");
    private static final Pattern NOT_CONFIRM = Pattern.compile("(?i)cancel|back|no\\b|decline|hủy|không|✘|✖|close");

    private static final class Route {
        final List<String> steps;
        final int score;
        final long seq;

        Route(List<String> steps, int score, long seq) {
            this.steps = steps;
            this.score = score;
            this.seq = seq;
        }

        String key() {
            return String.join(">", steps);
        }

        int pages() {
            int n = 0;
            for (String s : steps) if (s.equals(NEXT)) n++;
            return n;
        }

        int depth() {
            return steps.size() - pages();
        }
    }

    private enum Phase { INIT, WAIT_BALANCE, CLOSING, OPENING, FOLLOW, ANALYZE, BUY }

    private enum Click { NONE, LISTING, QTY, CONFIRM }

    // ---- configuration -------------------------------------------------------------------
    private final ShopSettings cfg;
    private final Mode mode;
    private final String target;
    private final int startHave;
    private final int goal;
    private final ShopCatalog catalog;
    private final Listener listener;

    // ---- result --------------------------------------------------------------------------
    private boolean finished;
    private boolean ok;
    private String message = "";

    // ---- state ---------------------------------------------------------------------------
    private Phase phase = Phase.INIT;
    private int tick;
    private int startTick = -1;
    private int actions;
    private int screensSeen;
    private int have;
    private ShopScreen screen;

    private boolean waiting;
    private boolean timedOut;
    private int waitStart;
    private int baseId = -1;
    private int baseSig;
    private int baseHave;

    private double balance = Double.NaN;
    private int balanceDeadline;

    private final PriorityQueue<Route> queue = new PriorityQueue<>(
            (a, b) -> a.score != b.score ? Integer.compare(b.score, a.score) : Long.compare(a.seq, b.seq));
    private final Set<String> known = new HashSet<>();
    private long seq;
    private Route current;
    private Route analyzed;
    private int stepIdx;
    private int openFailures;

    // buying
    private String buyKey = "";
    private Route buyRoute;
    private int buyBefore;
    private int buyAttempts;
    private int reopens;
    private Click lastClick = Click.NONE;
    private boolean clickedShift;
    private boolean triedShift;
    private boolean preferShift;
    private int lastQtyShown = -1;
    private boolean qtyStuck;
    private boolean chatFail;
    private String chatFailText = "";

    public ShopBrain(Mode mode, String target, int missing, int haveNow, ShopSettings cfg,
                     ShopCatalog catalog, Listener listener) {
        this.mode = mode;
        this.target = target == null ? null : target.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        this.startHave = haveNow;
        this.goal = haveNow + Math.max(0, missing);
        this.cfg = cfg;
        this.catalog = catalog;
        this.listener = listener;

        if (mode == Mode.BUY && catalog != null) {
            ShopCatalog.Entry hint = catalog.get(this.target);
            if (hint != null) {
                push(new ArrayList<>(hint.route), Integer.MAX_VALUE);       // known location first
            }
        }
        push(new ArrayList<>(), Integer.MAX_VALUE - 1);                      // the /shop root
    }

    // =========================================================================================
    // public API
    // =========================================================================================

    public boolean isFinished() {
        return finished;
    }

    public boolean isOk() {
        return ok;
    }

    public String message() {
        return message;
    }

    public double balance() {
        return balance;
    }

    public int screensSeen() {
        return screensSeen;
    }

    public int bought(int haveNow) {
        return Math.max(0, haveNow - startHave);
    }

    /** Feed every incoming chat/system line here. */
    public void onChat(String raw) {
        if (finished || raw == null) {
            return;
        }
        String low = Money.stripColors(raw).toLowerCase(Locale.ROOT);
        if (phase == Phase.WAIT_BALANCE && Double.isNaN(balance)
                && containsAny(low, "bal", "money", "cash", "coin", "$", "tiền", "funds", "wallet")) {
            double v = Money.parseCurrency(raw);
            if (!Double.isNaN(v)) {
                balance = v;
            }
        }
        if (phase == Phase.BUY) {
            for (String p : cfg.failPhrases) {
                if (low.contains(p.toLowerCase(Locale.ROOT))) {
                    chatFail = true;
                    chatFailText = Money.stripColors(raw).trim();
                    return;
                }
            }
        }
    }

    /**
     * Called once per client tick.
     *
     * @param scr  the open shop container, or null if no container is open
     * @param haveNow how many of the target item are in the inventory right now
     */
    public ShopAction next(ShopScreen scr, int haveNow) {
        tick++;
        if (finished) {
            return ShopAction.WAIT;
        }
        if (startTick < 0) {
            startTick = tick;
        }
        this.have = haveNow;
        this.screen = scr;

        if (tick - startTick > cfg.maxTicks) return fail("Timed out.");
        if (actions > cfg.maxActions) return fail("Gave up (too many clicks).");
        if (mode == Mode.BUY && have >= goal) {
            return succeed("Have " + have + "x " + target + ".");
        }

        if (waiting) {
            boolean screenChanged = scr == null ? baseId != -1 : (scr.id != baseId || scr.sig() != baseSig);
            int elapsed = tick - waitStart;
            if ((screenChanged || have != baseHave) && elapsed >= cfg.settleMinTicks) {
                waiting = false;
            } else if (elapsed >= cfg.waitMaxTicks) {
                waiting = false;
                timedOut = true;
            } else {
                return ShopAction.WAIT;
            }
        }
        ShopAction a = step();
        timedOut = false;
        return a;
    }

    // =========================================================================================
    // state machine
    // =========================================================================================

    private ShopAction step() {
        switch (phase) {
            case INIT:
                if (mode == Mode.BUY && cfg.checkBalance && Double.isNaN(balance)) {
                    phase = Phase.WAIT_BALANCE;
                    balanceDeadline = tick + 50;
                    actions++;
                    return ShopAction.command(cfg.balanceCommand);
                }
                return beginNextRoute();

            case WAIT_BALANCE:
                if (!Double.isNaN(balance) || tick >= balanceDeadline) {
                    return beginNextRoute();
                }
                return ShopAction.WAIT;

            case CLOSING:
                if (screen != null) {
                    return ShopAction.CLOSE;
                }
                return openShop();

            case OPENING:
                if (screen == null) {
                    if (++openFailures >= 3) {
                        return fail("/" + cfg.shopCommand + " did not open a menu.");
                    }
                    return openShop();
                }
                openFailures = 0;
                phase = Phase.FOLLOW;
                stepIdx = 0;
                return step();

            case FOLLOW:
                return follow();

            case ANALYZE:
                return analyze();

            case BUY:
                return buy();

            default:
                return ShopAction.WAIT;
        }
    }

    private ShopAction openShop() {
        phase = Phase.OPENING;
        actions++;
        startWait();
        return ShopAction.command(cfg.shopCommand);
    }

    // ---- exploring --------------------------------------------------------------------------

    private ShopAction beginNextRoute() {
        Route r = poll();
        if (r == null) {
            if (mode == Mode.SCAN) {
                return succeed("Scan complete: " + screensSeen + " menus, "
                        + (catalog == null ? 0 : catalog.items.size()) + " items.");
            }
            return fail("'" + target + "' was not found in /" + cfg.shopCommand + " (looked at " + screensSeen + " menus).");
        }
        Route prev = current;
        current = r;
        stepIdx = 0;
        // still standing on the menu we just analysed and the next route is one click further -> no reopen
        if (analyzed != null && analyzed == prev && screen != null
                && r.steps.size() == prev.steps.size() + 1 && r.steps.subList(0, prev.steps.size()).equals(prev.steps)) {
            stepIdx = prev.steps.size();
            phase = Phase.FOLLOW;
            return follow();
        }
        analyzed = null;
        if (screen != null) {
            phase = Phase.CLOSING;
            return ShopAction.CLOSE;
        }
        return openShop();
    }

    private ShopAction follow() {
        if (screen == null) {
            return beginNextRoute();                      // menu vanished mid-route
        }
        if (timedOut && stepIdx > 0) {
            return beginNextRoute();                      // last click changed nothing -> dead end
        }
        if (stepIdx >= current.steps.size()) {
            phase = Phase.ANALYZE;
            return analyze();
        }
        String key = current.steps.get(stepIdx);
        ShopSlot s = key.equals(NEXT) ? findNext(screen) : screen.slotWithKey(key);
        if (s == null) {
            return beginNextRoute();                      // stale route
        }
        stepIdx++;
        return click(s.index, false);
    }

    private ShopAction analyze() {
        screensSeen++;
        if (screensSeen > cfg.maxScreens) {
            return fail("Stopped after " + cfg.maxScreens + " menus without finding it.");
        }
        analyzed = current;
        if (listener != null) {
            listener.onScreen(current.steps, screen);
        }
        if (catalog != null) {
            for (ShopSlot s : screen.slots) {
                if (s.isProduct()) {
                    catalog.putIfAbsent(s.itemId, s.name, s.price(), current.steps);
                }
            }
        }
        if (mode == Mode.BUY) {
            ShopSlot t = findTarget(screen);
            if (t != null) {
                return beginBuy(t);
            }
        }
        // expand: next page first (finish this category), then sub-menus
        ShopSlot next = findNext(screen);
        if (next != null && current.pages() < cfg.maxPagesPerCategory) {
            push(appended(current.steps, NEXT), Integer.MAX_VALUE / 2);
        }
        if (current.depth() < cfg.maxDepth) {
            for (ShopSlot s : screen.slots) {
                if (s.isProduct() || ItemHints.isExcludedNav(s)) continue;
                int score = ItemHints.navScore(target, s);
                if (score >= 0) {
                    push(appended(current.steps, s.key()), score);
                }
            }
        }
        return beginNextRoute();
    }

    // ---- buying -----------------------------------------------------------------------------

    private ShopAction beginBuy(ShopSlot t) {
        int remaining = goal - have;
        double unit = Money.unitPrice(t.lore, t.count);
        if (!Double.isNaN(unit)) {
            double total = unit * remaining;
            if (!Double.isNaN(balance) && total > balance) {
                return fail("Not enough money for " + remaining + "x " + target + ": needs about "
                        + Money.format(total) + ", balance " + Money.format(balance) + ". Nothing bought.");
            }
            if (cfg.maxSpend > 0 && total > cfg.maxSpend) {
                return fail("Cost " + Money.format(total) + " is above your spend cap " + Money.format(cfg.maxSpend) + ". Nothing bought.");
            }
        }
        phase = Phase.BUY;
        buyRoute = current;
        buyKey = t.key();
        buyBefore = have;
        buyAttempts = 0;
        return listingClick(t);
    }

    private ShopAction buy() {
        if (chatFail) {
            return fail("The server refused the purchase: \"" + chatFailText + "\". Nothing more bought.");
        }
        if (lastClick == Click.LISTING || lastClick == Click.CONFIRM) {
            if (have > buyBefore) {
                int delta = have - buyBefore;
                if (clickedShift && delta > 1) preferShift = true;
                if (clickedShift && delta <= 1) preferShift = false;
                buyBefore = have;
                buyAttempts = 0;
                reopens = 0;                                  // making progress: reopening the menu is normal
            } else {
                buyAttempts++;
                if (clickedShift) {
                    preferShift = false;
                }
            }
            if (buyAttempts > 3) {
                return fail("Clicking the item did not give it to you (out of stock, full inventory, or the menu works differently). "
                        + "Run /farmshop scan and send me farmshop-scan.txt.");
            }
        }
        return decideBuyClick();
    }

    private ShopAction decideBuyClick() {
        if (screen == null) {
            if (++reopens > 6) return fail("Kept losing the shop menu while buying.");
            requeue(buyRoute);
            return beginNextRoute();
        }
        ShopSlot confirm = findConfirm(screen);
        if (confirm != null) {
            return detailClick(confirm);
        }
        ShopSlot p = screen.slotWithKey(buyKey);
        if (p == null) {
            p = findTarget(screen);
        }
        if (p != null) {
            return listingClick(p);
        }
        if (++reopens > 6) return fail("Lost the item's menu while buying.");
        requeue(buyRoute);
        return beginNextRoute();
    }

    private ShopAction listingClick(ShopSlot p) {
        int remaining = goal - have;
        // shift-click (usually "buy a stack") only while at least a whole stack is still missing - never overbuy
        boolean shift = remaining >= 64 && (preferShift || (!triedShift && have > startHave));
        if (shift) triedShift = true;
        clickedShift = shift;
        lastClick = Click.LISTING;
        return click(p.index, shift);
    }

    private ShopAction detailClick(ShopSlot confirm) {
        int remaining = goal - have;
        ShopSlot preview = null;
        for (ShopSlot s : screen.slots) {
            if (s.itemId.equals(target)) { preview = s; break; }
        }
        int shown = preview == null ? -1 : preview.count;
        if (lastQtyShown >= 0 && shown == lastQtyShown) {
            qtyStuck = true;                           // pressing "+N" did not move the number
        }
        lastQtyShown = -1;

        if (shown >= 0 && shown < remaining && !qtyStuck) {
            ShopSlot best = null;
            int bestN = 0;
            ShopSlot smallest = null;
            int smallestN = Integer.MAX_VALUE;
            for (ShopSlot s : screen.slots) {
                Matcher m = QTY_ADD.matcher(s.name);
                if (!m.find()) continue;
                int n = Integer.parseInt(m.group(1));
                if (n <= 0) continue;
                if (shown + n <= remaining && n > bestN) { best = s; bestN = n; }
                if (n < smallestN) { smallest = s; smallestN = n; }
            }
            ShopSlot pick = best != null ? best : smallest;
            if (pick != null) {
                lastQtyShown = shown;
                lastClick = Click.QTY;
                clickedShift = false;
                return click(pick.index, false);
            }
        }
        lastClick = Click.CONFIRM;
        clickedShift = false;
        return click(confirm.index, false);
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** A priced slot holding the wanted item. Unpriced copies are previews / category icons, not products. */
    private ShopSlot findTarget(ShopScreen s) {
        for (ShopSlot x : s.slots) {
            if (x.itemId.equals(target) && x.isProduct()) return x;
        }
        return null;
    }

    private ShopSlot findNext(ShopScreen s) {
        for (ShopSlot x : s.slots) {
            if (!x.isProduct() && ItemHints.isNext(x)) return x;
        }
        return null;
    }

    private ShopSlot findConfirm(ShopScreen s) {
        for (ShopSlot x : s.slots) {
            if (x.isProduct() || x.itemId.equals(target) || x.blankName()) continue;
            if (NOT_CONFIRM.matcher(x.name).find()) continue;
            if (CONFIRM.matcher(x.name).find()) return x;
        }
        return null;
    }

    private void push(List<String> steps, int score) {
        Route r = new Route(steps, score, seq++);
        if (known.add(r.key())) {
            queue.add(r);
        }
    }

    /** Put a route back at the front even though it was visited before. */
    private void requeue(Route r) {
        queue.add(new Route(r.steps, Integer.MAX_VALUE, seq++));
    }

    private Route poll() {
        return queue.poll();
    }

    private static List<String> appended(List<String> base, String step) {
        List<String> l = new ArrayList<>(base);
        l.add(step);
        return l;
    }

    private void startWait() {
        waiting = true;
        timedOut = false;
        waitStart = tick;
        baseId = screen == null ? -1 : screen.id;
        baseSig = screen == null ? 0 : screen.sig();
        baseHave = have;
    }

    private ShopAction click(int slot, boolean shift) {
        actions++;
        startWait();
        return ShopAction.click(slot, shift);
    }

    private ShopAction succeed(String msg) {
        ok = true;
        message = msg;
        finished = true;
        return screen != null ? ShopAction.CLOSE : ShopAction.WAIT;
    }

    private ShopAction fail(String msg) {
        ok = false;
        message = msg;
        finished = true;
        return screen != null ? ShopAction.CLOSE : ShopAction.WAIT;
    }

    private static boolean containsAny(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }
}
