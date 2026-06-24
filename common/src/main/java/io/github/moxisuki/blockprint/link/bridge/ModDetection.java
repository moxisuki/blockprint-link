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

    private static final AtomicBoolean PROBED = new AtomicBoolean(false);
    private static volatile boolean cachedResult = false;
    private static volatile boolean lastReportedState = false;

    private ModDetection() {}

    /**
     * @return true iff WorldEdit is loaded. Returns the cached result
     *         after the first call; use {@link #invalidateCache()} to
     *         force a fresh probe.
     */
    public static boolean isWorldEditLoaded() {
        if (PROBED.get()) return cachedResult;
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
        if (PROBED.get()) return cachedResult;  // double-checked
        String via = probeViaModList();
        boolean found = (via != null) || probeViaClasspath();
        cachedResult = found;
        PROBED.set(true);
        // Log exactly once on the false↔true transition.
        if (found != lastReportedState) {
            if (found) {
                LogUtil.info("[BlockPrintLink/Bridge] WorldEdit detected"
                    + (via != null ? " (via " + via + ")" : " (via classpath)")
                    + " — watching config/worldedit/schematics");
            } else {
                LogUtil.info("[BlockPrintLink/Bridge] WorldEdit not loaded");
            }
            lastReportedState = found;
        }
        return found;
    }

    /**
     * Try each loader's canonical mod-list API. Returns the loader name
     * on success (so the transition log can show which path worked),
     * or {@code null} if no API is reachable.
     */
    private static String probeViaModList() {
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
                    .invoke(modList, WORLDEDIT_MOD_ID);
                if (Boolean.TRUE.equals(isLoaded)) return modListNames[i];
            } catch (Throwable ignored) {}
        }
        // Fabric: FabricLoader.getInstance().isModLoaded(id).
        try {
            Class<?> cls = Class.forName("net.fabricmc.loader.api.FabricLoader", false, probeLoader);
            Object inst = cls.getMethod("getInstance").invoke(null);
            Object isLoaded = cls.getMethod("isModLoaded", String.class)
                .invoke(inst, WORLDEDIT_MOD_ID);
            if (Boolean.TRUE.equals(isLoaded)) return "Fabric FabricLoader";
        } catch (Throwable ignored) {}
        try {
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            if (ctx != null && ctx != probeLoader) {
                Class<?> cls = Class.forName("net.fabricmc.loader.api.FabricLoader", false, ctx);
                Object inst = cls.getMethod("getInstance").invoke(null);
                Object isLoaded = cls.getMethod("isModLoaded", String.class)
                    .invoke(inst, WORLDEDIT_MOD_ID);
                if (Boolean.TRUE.equals(isLoaded)) return "Fabric FabricLoader (via ctx loader)";
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Last-resort classpath probe. Walks up the classloader chain to
     * find one that has the WE main class on it.
     */
    private static boolean probeViaClasspath() {
        ClassLoader cl = ModDetection.class.getClassLoader();
        while (cl != null) {
            try {
                Class.forName("com.sk89q.worldedit.WorldEdit", false, cl);
                return true;
            } catch (Throwable ignored) {}
            cl = cl.getParent();
        }
        return false;
    }
}
