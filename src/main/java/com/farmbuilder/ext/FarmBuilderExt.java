package com.farmbuilder.ext;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

/**
 * Second client entrypoint (registered after the original FarmBuilderClient):
 *  - /farmschem commands
 *  - drag-and-drop schematic import
 *  - creative-menu supply (applied through the mixins in com.farmbuilder.mixin)
 */
public class FarmBuilderExt implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ExtConfig.load();
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> SchematicCommands.register(dispatcher));
        ClientLifecycleEvents.CLIENT_STARTED.register(DropHandler::install);
        System.out.println("[FarmBuilder] Extensions ready: /farmschem, schematic drag&drop, creative supply = " + ExtConfig.creativeSupply);
    }
}
