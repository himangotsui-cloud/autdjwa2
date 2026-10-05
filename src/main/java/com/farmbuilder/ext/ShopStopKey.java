package com.farmbuilder.ext;

import com.farmbuilder.ext.shop.ShopEngine;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWKeyCallback;

/**
 * Emergency brake for the shop bot. Chains in front of the game's own key handler, so it sees EVERY key press
 * (polling once per tick can miss a quick tap). Esc or the stop key (default Delete) stops the shop bot and turns
 * Shop mode off; the key still reaches the game, so Esc also closes the menu as usual.
 */
public final class ShopStopKey {
    private static boolean installed;
    private static GLFWKeyCallback previous;

    private ShopStopKey() {
    }

    public static void install(MinecraftClient client) {
        if (installed) {
            return;
        }
        installed = true;
        long window = client.getWindow().getHandle();
        previous = GLFW.glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (action == GLFW.GLFW_PRESS && (key == GLFW.GLFW_KEY_ESCAPE || key == ExtConfig.shopStopKey)
                    && ShopEngine.isBusy()) {
                ShopEngine.requestStop();
            }
            if (previous != null) {
                previous.invoke(win, key, scancode, action, mods);
            }
        });
    }
}
