package com.farmbuilder.ext;

import com.farmbuilder.ext.shop.ShopCommands;
import com.farmbuilder.ext.shop.ShopEngine;
import com.farmbuilder.ext.shop.ShopGuiPatch;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/**
 * Second client entrypoint (registered after the original FarmBuilderClient):
 *  - /farmschem commands + schematic drag&drop
 *  - creative-menu supply (applied through the mixins in com.farmbuilder.mixin)
 *  - /shop auto-buy (Shop toggle in the menu, /farmshop commands)
 */
public class FarmBuilderExt implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ExtConfig.load();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            SchematicCommands.register(dispatcher);
            ShopCommands.register(dispatcher);
        });
        ClientLifecycleEvents.CLIENT_STARTED.register(DropHandler::install);

        ClientTickEvents.END_CLIENT_TICK.register(ShopEngine::tick);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> ShopEngine.onChat(message.getString()));
        ShopGuiPatch.register();

        System.out.println("[FarmBuilder] Extensions ready: /farmschem, /farmshop, schematic drag&drop, creative supply = "
                + ExtConfig.creativeSupply + ", shop mode = " + ExtConfig.shopEnabled);
    }
}
