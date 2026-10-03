import com.farmbuilder.ext.schem.SchematicConverter;

import java.nio.file.Path;

/** Command line wrapper: java SchemTool <schematic> <outDir> [name] */
public class SchemTool {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: SchemTool <schematic> <outDir> [name]");
            System.exit(2);
        }
        try {
            SchematicConverter.Result r = SchematicConverter.convert(
                    Path.of(args[0]), Path.of(args[1]), args.length > 2 ? args[2] : null);
            System.out.println(r.format + ": " + r.blocks + " blocks, " + r.sizeX + "x" + r.sizeY + "x" + r.sizeZ + " -> " + r.file);
        } catch (java.io.IOException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }
}
