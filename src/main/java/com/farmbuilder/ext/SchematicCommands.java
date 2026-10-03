package com.farmbuilder.ext;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandSource;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /farmschem list | load &lt;file&gt; | import &lt;file&gt; | folder | creative [on|off]
 */
public final class SchematicCommands {
    private SchematicCommands() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommandManager.literal("farmschem")
                .then(ClientCommandManager.literal("list").executes(ctx -> list(ctx.getSource())))
                .then(ClientCommandManager.literal("folder").executes(ctx -> folder(ctx.getSource())))
                .then(ClientCommandManager.literal("load")
                        .then(ClientCommandManager.argument("file", StringArgumentType.greedyString())
                                .suggests((ctx, b) -> CommandSource.suggestMatching(names(), b))
                                .executes(ctx -> run(ctx, true))))
                .then(ClientCommandManager.literal("import")
                        .then(ClientCommandManager.argument("file", StringArgumentType.greedyString())
                                .suggests((ctx, b) -> CommandSource.suggestMatching(names(), b))
                                .executes(ctx -> run(ctx, false))))
                .then(ClientCommandManager.literal("creative")
                        .executes(ctx -> creative(ctx.getSource(), null))
                        .then(ClientCommandManager.literal("on").executes(ctx -> creative(ctx.getSource(), true)))
                        .then(ClientCommandManager.literal("off").executes(ctx -> creative(ctx.getSource(), false))))
                .executes(ctx -> help(ctx.getSource())));
    }

    private static List<String> names() {
        return SchematicImporter.listSchematics().stream()
                .map(p -> p.getFileName().toString()).collect(Collectors.toList());
    }

    private static int run(CommandContext<FabricClientCommandSource> ctx, boolean load) {
        String arg = StringArgumentType.getString(ctx, "file");
        Path p = SchematicImporter.resolve(arg);
        if (p == null) {
            ctx.getSource().sendFeedback(Text.literal("§c[FarmSchem] " + LegacyBridge.tr("Không tìm thấy: ", "Not found: ") + arg
                    + "  (/farmschem list)"));
            return 0;
        }
        SchematicImporter.importAsync(p, load);
        return 1;
    }

    private static int list(FabricClientCommandSource src) {
        List<String> n = names();
        if (n.isEmpty()) {
            src.sendFeedback(Text.literal("§e[FarmSchem] " + LegacyBridge.tr("Chưa có schematic nào. Thả file vào cửa sổ game hoặc bỏ vào: ",
                    "No schematics yet. Drag a file onto the game window or put it in: ") + SchematicImporter.litematicaDir()));
            return 0;
        }
        src.sendFeedback(Text.literal("§6[FarmSchem] " + n.size() + " schematic(s):"));
        for (String s : n) {
            src.sendFeedback(Text.literal("§7 - §f" + s));
        }
        return n.size();
    }

    private static int folder(FabricClientCommandSource src) {
        src.sendFeedback(Text.literal("§6[FarmSchem] Schematics: §f" + SchematicImporter.litematicaDir()));
        src.sendFeedback(Text.literal("§6[FarmSchem] Dropped files: §f" + SchematicImporter.dropDir()));
        src.sendFeedback(Text.literal("§6[FarmSchem] Blueprints: §f" + SchematicImporter.farmsDir()));
        return 1;
    }

    private static int creative(FabricClientCommandSource src, Boolean set) {
        if (set != null) {
            ExtConfig.creativeSupply = set;
            ExtConfig.save();
        }
        src.sendFeedback(Text.literal("§6[FarmSchem] " + LegacyBridge.tr("Lấy block từ menu Creative (singleplayer): ",
                "Creative-menu supply (singleplayer only): ") + (ExtConfig.creativeSupply ? "§aON" : "§cOFF")));
        return 1;
    }

    private static int help(FabricClientCommandSource src) {
        src.sendFeedback(Text.literal("§6/farmschem list §7- list schematics"));
        src.sendFeedback(Text.literal("§6/farmschem load <file> §7- convert + load into the builder"));
        src.sendFeedback(Text.literal("§6/farmschem import <file> §7- convert only"));
        src.sendFeedback(Text.literal("§6/farmschem creative [on|off] §7- creative-menu supply"));
        src.sendFeedback(Text.literal("§6/farmschem folder §7- show folders"));
        src.sendFeedback(Text.literal("§7Or just drag a .litematic / .schem / .nbt onto the game window."));
        return 1;
    }
}
