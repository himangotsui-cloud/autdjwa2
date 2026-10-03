package com.farmbuilder.ext;

import com.farmbuilder.ext.schem.SchematicConverter;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWDropCallback;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Drag a .litematic / .schem / .nbt onto the Minecraft window to import it.
 *
 * Chains in front of the game's own drop handler: schematics are consumed here, anything
 * else (e.g. resource packs) is passed straight through to vanilla untouched.
 */
public final class DropHandler {
    private static boolean installed;
    /** The callback vanilla had registered before us; non-schematic drops are forwarded to it. */
    private static GLFWDropCallback previous;

    private DropHandler() {
    }

    public static void install(MinecraftClient client) {
        if (installed) {
            return;
        }
        installed = true;
        long window = client.getWindow().getHandle();
        previous = GLFW.glfwSetDropCallback(window, (win, count, names) -> {
            List<Path> mine = new ArrayList<>();
            boolean other = false;
            for (int i = 0; i < count; i++) {
                String name = GLFWDropCallback.getName(names, i);
                if (SchematicConverter.isSchematicFile(name)) {
                    mine.add(Path.of(name));
                } else {
                    other = true;
                }
            }
            if (!mine.isEmpty()) {
                client.execute(() -> {
                    for (Path p : mine) {
                        SchematicImporter.importAsync(SchematicImporter.keepCopy(p), ExtConfig.autoLoadDropped);
                    }
                });
            }
            // hand non-schematic drops (or a mixed drop) back to vanilla
            if ((mine.isEmpty() || other) && previous != null) {
                previous.invoke(win, count, names);
            }
        });
    }
}
