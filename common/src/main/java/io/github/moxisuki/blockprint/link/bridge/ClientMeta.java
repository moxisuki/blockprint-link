package io.github.moxisuki.blockprint.link.bridge;

import java.io.File;

/**
 * Platform info about the Minecraft client running the bridge.
 * Gathered via reflection so the common module doesn't need
 * per-loader imports.
 */
public final class ClientMeta {

    // Cached at first call
    private static String mcVersion;
    private static String loader;
    private static String loaderVersion;

    private ClientMeta() {}

    /**
     * Called by each platform's @Mod constructor to inject the
     * MC version. This bypasses the reflection-based
     * {@link #mcVersion()} fallback which can't work on SRG-named
     * runtimes (Forge 1.20.1).
     */
    public static void setMcVersion(String v) { mcVersion = v; }
    public static void setLoader(String l) { loader = l; }
    public static void setLoaderVersion(String v) { loaderVersion = v; }

    public static String mcVersion() {
        if (mcVersion != null) return mcVersion;
        try {
            // 1.21.x: SharedConstants.getCurrentVersion().getName()
            // 26.x:   SharedConstants.getCurrentVersion().name()
            Class<?> c = Class.forName("net.minecraft.SharedConstants");
            Object ver = c.getMethod("getCurrentVersion").invoke(null);
            try {
                mcVersion = (String) ver.getClass().getMethod("getName").invoke(ver);
            } catch (NoSuchMethodException e) {
                mcVersion = (String) ver.getClass().getMethod("name").invoke(ver);
            }
        } catch (Throwable e) {
            mcVersion = "unknown";
        }
        return mcVersion;
    }

    public static String loader() {
        if (loader != null) return loader;
        // NeoForge (1.20.2+)
        if (classExists("net.neoforged.fml.loading.FMLLoader")) { loader = "neoforge"; return loader; }
        // Forge (1.20.1)
        if (classExists("net.minecraftforge.fml.loading.FMLLoader")) { loader = "forge"; return loader; }
        // Fabric
        if (classExists("net.fabricmc.loader.api.FabricLoader")) { loader = "fabric"; return loader; }
        loader = "vanilla";
        return loader;
    }

    private static boolean classExists(String name) {
        try { Class.forName(name); return true; } catch (Throwable t) { return false; }
    }

    public static String loaderVersion() {
        if (loaderVersion != null) return loaderVersion;
        // NeoForge ModList
        try { loaderVersion = modListVersion("net.neoforged.fml.ModList", "neoforge"); return loaderVersion; } catch (Throwable ignored) {}
        // Forge ModList
        try { loaderVersion = modListVersion("net.minecraftforge.fml.ModList", "forge"); return loaderVersion; } catch (Throwable ignored) {}
        // Fabric
        try {
            Class<?> c = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object inst = c.getMethod("getInstance").invoke(c);
            Object mc = c.getMethod("getModContainer", String.class).invoke(inst, "fabricloader");
            if (mc != null) {
                Object meta = mc.getClass().getMethod("getMetadata").invoke(mc);
                loaderVersion = meta.getClass().getMethod("getVersion").invoke(meta).toString();
                return loaderVersion;
            }
        } catch (Throwable ignored) {}
        loaderVersion = "0";
        return loaderVersion;
    }

    private static String modListVersion(String modListClass, String modId) throws Exception {
        Class<?> c = Class.forName(modListClass);
        Object ml = c.getMethod("get").invoke(null);
        Object mc = ml.getClass().getMethod("getModContainerById", String.class).invoke(ml, modId);
        if (mc != null && mc.getClass().getMethod("isPresent").invoke(mc).equals(true)) {
            Object info = mc.getClass().getMethod("get").invoke(mc);
            Object modInfo = info.getClass().getMethod("getModInfo").invoke(info);
            return modInfo.getClass().getMethod("getVersion").invoke(modInfo).toString();
        }
        return "0";
    }

    private static File schematicsDir() {
        try {
            Class<?> c = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object inst = c.getMethod("getInstance").invoke(null);
            Object path = c.getMethod("getGameDir").invoke(inst);
            return new File(((java.nio.file.Path) path).toFile(), "schematics");
        } catch (Throwable ignored) {}
        try {
            Class<?> c = Class.forName("net.neoforged.fml.loading.FMLPaths");
            Object gd = c.getField("GAMEDIR").get(null);
            Object path = gd.getClass().getMethod("get").invoke(gd);
            return new File(((java.nio.file.Path) path).toFile(), "schematics");
        } catch (Throwable ignored) {}
        try {
            Class<?> c = Class.forName("net.minecraftforge.fml.loading.FMLPaths");
            Object gd = c.getField("GAMEDIR").get(null);
            Object path = gd.getClass().getMethod("get").invoke(gd);
            return new File(((java.nio.file.Path) path).toFile(), "schematics");
        } catch (Throwable ignored) {}
        return null;
    }

    /** The game version folder name, e.g. "1.21.1-NeoForge_21.1.233". */
    public static String folderName(File schematicsDir) {
        File parent = schematicsDir.getParentFile();
        return parent != null ? parent.getName() : "unknown";
    }
}
