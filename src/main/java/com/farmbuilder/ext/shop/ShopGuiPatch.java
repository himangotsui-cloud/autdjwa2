package com.farmbuilder.ext.shop;

import com.farmbuilder.ext.ExtConfig;
import com.farmbuilder.ext.LegacyBridge;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Edits the original (precompiled) menu in place, once per frame, without touching its code:
 *   - the "Auto Sell Empty Buckets (/sell bucket)" toggle becomes the "Shop" toggle
 *   - the "Sell All Empty Buckets" button becomes "Scan /shop"
 * The menu rebuilds its widgets when you switch tabs; the next frame patches the fresh ones again.
 */
public final class ShopGuiPatch {
    private static final String GUI_TOGGLE = "com.farmbuilder.gui.components.GuiToggle";
    private static final Set<Screen> HOOKED = Collections.newSetFromMap(new WeakHashMap<>());

    private ShopGuiPatch() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!"com.farmbuilder.gui.FarmBuilderScreen".equals(screen.getClass().getName()) || !HOOKED.add(screen)) {
                return;
            }
            ScreenEvents.beforeRender(screen).register((s, ctx, mx, my, delta) -> patch(s));
        });
    }

    private static void patch(Screen screen) {
        try {
            for (Element e : screen.children()) {
                if (e.getClass().getName().equals(GUI_TOGGLE)) {
                    String label = (String) field(e, "label").get(e);
                    if (label != null && label.toLowerCase(Locale.ROOT).contains("/sell")) {
                        retargetToggle(screen, e);
                    }
                } else if (e instanceof ButtonWidget b) {
                    String m = b.getMessage().getString().toLowerCase(Locale.ROOT);
                    if (m.contains("sell buckets") || m.contains("sell all empty") || m.contains("bán toàn bộ xô")) {
                        retargetButton(b);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // never break the menu over a cosmetic patch
        }
    }

    @SuppressWarnings("unchecked")
    private static void retargetToggle(Screen screen, Object toggle) throws ReflectiveOperationException {
        field(toggle, "label").set(toggle, LegacyBridge.tr("Shop: tự /shop mua đồ thiếu", "Shop: auto-buy missing items (/shop)"));
        field(toggle, "description").set(toggle, LegacyBridge.tr(
                "Bật: khi hết vật liệu sẽ mở /shop, quét cửa hàng, tìm đúng món và mua (thay /order). Không đủ tiền thì không mua.",
                "ON: when materials run out, open /shop, find the item and buy it instead of /order. Not enough money = nothing is bought."));
        Consumer<Boolean> onChange = on -> {
            ShopCommands.applyEnabled(on);
            if (on) {
                syncOrderToggle(screen);
            }
        };
        field(toggle, "onChange").set(toggle, onChange);
        Method setState = toggle.getClass().getMethod("setState", boolean.class);
        setState.invoke(toggle, ExtConfig.shopEnabled);
    }

    /** Shop mode switches "Auto Order Materials" on (the builder only asks for materials when it is on): show that. */
    private static void syncOrderToggle(Screen screen) {
        try {
            for (Element e : screen.children()) {
                if (!e.getClass().getName().equals(GUI_TOGGLE)) continue;
                String label = String.valueOf(field(e, "label").get(e)).toLowerCase(Locale.ROOT);
                if (label.contains("/order)") && (label.contains("materials") || label.contains("vật liệu"))) {
                    e.getClass().getMethod("setState", boolean.class).invoke(e, true);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // cosmetic only
        }
    }

    private static void retargetButton(ButtonWidget b) throws ReflectiveOperationException {
        b.setMessage(Text.literal(LegacyBridge.tr("Quét /shop (lưu danh mục)", "Scan /shop (build catalog)")));
        for (Field f : ButtonWidget.class.getDeclaredFields()) {
            if (f.getType() == ButtonWidget.PressAction.class) {
                f.setAccessible(true);
                f.set(b, (ButtonWidget.PressAction) btn -> ShopEngine.startScan());
            }
        }
    }

    private static Field field(Object o, String name) throws NoSuchFieldException {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
