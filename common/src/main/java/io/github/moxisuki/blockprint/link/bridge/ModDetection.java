package io.github.moxisuki.blockprint.link.bridge;

import io.github.moxisuki.blockprint.link.LogUtil;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cross-loader mod presence detection.
 *
 * Probes the canonical mod-list API from each loader via reflection.
 * These APIs return a boolean based on the mod's declared metadata, so
 * they work regardless of which classloader owns the mod's classes —
 * important because NeoForge/Forge put mod classes on a separate
 * ModClassLoader that's invisible to the main classloader.
 *
 * Result is cached after the first probe. The cache is invalidated by
 * {@link #invalidateCache()}, which {@code SchematicsWatcher.recheckWorldEdit}
 * calls from the {@code FMLLoadCompleteEvent} handler — at that point
 * every mod loader has finished constructing, so the second probe is
 * authoritative.
 *
 * Two probes per session total:
 *   1. {@code @Mod} constructor (might see WE as not-yet-loaded)
 *   2. {@code FMLLoadCompleteEvent} (always authoritative)
 * Every subsequent caller (file watcher rescans, player join, etc.)
 * gets the cached result. No log spam.
 */
public final class ModDetection {

    /** WorldEdit mod id — same string across Forge / NeoForge / Fabric. */
    private static final String WORLDEDIT_MOD_ID = "worldedit";
    /** Building Gadgets 2 mod id — same string across Forge / NeoForge / Fabric. */
    private static final String BG2_MOD_ID = "buildinggadgets2";
    /** Main class to fall back to if the mod-list API isn't reachable. */
    private static final String BG2_MAIN_CLASS = "com.direwolf20.buildinggadgets2.BuildingGadgets2";

    private static final AtomicBoolean PROBED = new AtomicBoolean(false);
    private static volatile boolean cachedWorldEdit = false;
    private static volatile boolean cachedBg2 = false;
    private static volatile boolean lastReportedWe = false;
    private static volatile boolean lastReportedBg2 = false;

    private ModDetection() {}

    /**
     * @return true iff WorldEdit is loaded. Returns the cached result
     *         after the first call; use {@link #invalidateCache()} to
     *         force a fresh probe.
     */
    public static boolean isWorldEditLoaded() {
        if (PROBED.get()) return cachedWorldEdit;
        return doProbe();
    }

    /**
     * @return true iff Building Gadgets 2 is loaded. Same caching /
     *         invalidation rules as {@link #isWorldEditLoaded()}.
     */
    public static boolean isBuildingGadgets2Loaded() {
        if (PROBED.get()) return cachedBg2;
        return doProbe();
    }

    /**
     * Force the next {@link #isWorldEditLoaded()} call to re-probe.
     * Called from the FMLLoadCompleteEvent handler so the cached
     * "not loaded" answer from the @Mod constructor (which can run
     * before WE is constructed) gets corrected to the final truth.
     */
    public static void invalidateCache() {
        synchronized (ModDetection.class) {
            PROBED.set(false);
        }
    }

    private static synchronized boolean doProbe() {
        if (PROBED.get()) return cachedWorldEdit;  // double-checked
        String weVia = probeModViaModList(WORLDEDIT_MOD_ID);
        boolean we = (weVia != null) || probeClasspath("com.sk89q.worldedit.WorldEdit");
        String bg2Via = probeModViaModList(BG2_MOD_ID);
        boolean bg2 = (bg2Via != null) || probeClasspath(BG2_MAIN_CLASS);
        cachedWorldEdit = we;
        cachedBg2 = bg2;
        PROBED.set(true);

        // Log exactly once on the false↔true transition for each mod.
        if (we != lastReportedWe) {
            if (we) {
                LogUtil.info("[BlockPrintLink/Bridge] WorldEdit detected"
                    + (weVia != null ? " (via " + weVia + ")" : " (via classpath)")
                    + " — watching config/worldedit/schematics");
            } else {
                LogUtil.info("[BlockPrintLink/Bridge] WorldEdit not loaded");
            }
            lastReportedWe = we;
        }
        if (bg2 != lastReportedBg2) {
            if (bg2) {
                LogUtil.info("[BlockPrintLink/Bridge] Building Gadgets 2 detected"
                    + (bg2Via != null ? " (via " + bg2Via + ")" : " (via classpath)"));
            } else {
                LogUtil.info("[BlockPrintLink/Bridge] Building Gadgets 2 not loaded");
            }
            lastReportedBg2 = bg2;
        }
        return cachedWorldEdit;
    }

    /**
     * Try each loader's canonical mod-list API for the given mod id.
     * Returns the loader name on success, or {@code null} if no API is
     * reachable for any loader.
     */
    private static String probeModViaModList(String modId) {
        ClassLoader probeLoader = ModDetection.class.getClassLoader();
        String[] modListClasses = {
            "net.neoforged.fml.ModList",      // NeoForge 1.20.2+
            "net.minecraftforge.fml.ModList"  // Forge 1.20.1+
        };
        String[] modListNames = { "NeoForge ModList", "Forge ModList" };
        for (int i = 0; i < modListClasses.length; i++) {
            try {
                Class<?> cls = Class.forName(modListClasses[i], false, probeLoader);
                Object modList = cls.getMethod("get").invoke(null);
                Object isLoaded = cls.getMethod("isLoaded", String.class)
                    .invoke(modList, modId);
                if (Boolean.TRUE.equals(isLoaded)) return modListNames[i];
            } catch (Throwable ignored) {}
        }
        // Fabric: FabricLoader.getInstance().isModLoaded(id).
        try {
            Class<?> cls = Class.forName("net.fabricmc.loader.api.FabricLoader", false, probeLoader);
            Object inst = cls.getMethod("getInstance").invoke(null);
            Object isLoaded = cls.getMethod("isModLoaded", String.class)
                .invoke(inst, modId);
            if (Boolean.TRUE.equals(isLoaded)) return "Fabric FabricLoader";
        } catch (Throwable ignored) {}
        try {
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            if (ctx != null && ctx != probeLoader) {
                Class<?> cls = Class.forName("net.fabricmc.loader.api.FabricLoader", false, ctx);
                Object inst = cls.getMethod("getInstance").invoke(null);
                Object isLoaded = cls.getMethod("isModLoaded", String.class)
                    .invoke(inst, modId);
                if (Boolean.TRUE.equals(isLoaded)) return "Fabric FabricLoader (via ctx loader)";
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Last-resort classpath probe. Walks up the classloader chain to
     * find one that has the given fully-qualified class on it.
     */
    private static boolean probeClasspath(String fqcn) {
        ClassLoader cl = ModDetection.class.getClassLoader();
        while (cl != null) {
            try {
                Class.forName(fqcn, false, cl);
                return true;
            } catch (Throwable ignored) {}
            cl = cl.getParent();
        }
        return false;
    }
}