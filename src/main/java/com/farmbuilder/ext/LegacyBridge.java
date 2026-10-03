package com.farmbuilder.ext;

import java.lang.reflect.Method;

/**
 * Calls into the original FarmBuilder classes by reflection, so the new code compiles
 * without needing those classes on the compile classpath.
 */
public final class LegacyBridge {
    private LegacyBridge() {
    }

    /** Equivalent to /farmbuild load &lt;name&gt; */
    public static void loadBlueprint(String name) throws ReflectiveOperationException {
        Class<?> engine = Class.forName("com.farmbuilder.build.AutoBuildEngine");
        engine.getMethod("loadBlueprint", String.class).invoke(null, name);
    }

    /** Uses the mod's own Vietnamese/English switch (I18n.tr(vi, en)); falls back to English. */
    public static String tr(String vi, String en) {
        try {
            Class<?> i18n = Class.forName("com.farmbuilder.util.I18n");
            Method m = i18n.getMethod("tr", String.class, String.class);
            return (String) m.invoke(null, vi, en);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return en;
        }
    }
}
