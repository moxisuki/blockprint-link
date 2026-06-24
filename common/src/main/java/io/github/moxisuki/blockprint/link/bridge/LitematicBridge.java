package io.github.moxisuki.blockprint.link.bridge;

import io.github.moxisuki.blockprint.link.LogUtil;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LitematicBridge {
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean STOPPED = new AtomicBoolean(false);
    private static volatile File resolvedGameDir;
    private static SchematicsWatcher watcher;
    private static SchematicsWsServer wsServer;
    private static DiscoveryBeacon beacon;

    /** Exposed for debug: the game directory resolved at startup. */
    public static File getGameDir() { return resolvedGameDir; }

    /**
     * Re-probe WorldEdit presence and re-register its schematics
     * directory if newly detected. Called from the player-join hook —
     * by that point every mod has finished constructing, so the result
     * is authoritative even when our @Mod constructor ran before WE.
     * Safe to call when the watcher isn't started; no-op.
     */
    public static void recheckWorldEdit() {
        SchematicsWatcher w = watcher;
        if (w != null) w.recheckWorldEdit();
    }

    private LitematicBridge() {}

    public static synchronized void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        if (!BridgeConfig.enabled()) {
            LogUtil.info("Bridge disabled by config; not starting");
            STARTED.set(false);
            return;
        }

        File gameDir = resolveGameDir();
        resolvedGameDir = gameDir;
        if (gameDir == null) {
            LogUtil.warn("gameDir not resolvable; skipping watcher/ws/beacon");
        } else {
            // Load config + persisted token BEFORE starting anything
            BridgeConfig.loadConfigFile(gameDir);
            BridgeConfig.initToken(gameDir);
            watcher = new SchematicsWatcher(gameDir);
            wsServer = new SchematicsWsServer(watcher);
            beacon = new DiscoveryBeacon();
            try { watcher.start(); } catch (Exception e) { LogUtil.error("watcher start", e); }
            try { wsServer.start(); } catch (Exception e) { LogUtil.error("ws start", e); }
            try { beacon.start(); } catch (Exception e) { LogUtil.error("beacon start", e); }
        }

        Runtime.getRuntime().addShutdownHook(new Thread(LitematicBridge::shutdown, "blockprintlink-shutdown"));
        LogUtil.info("Bridge started. Token: " + BridgeConfig.tokenHint() + "...");
    }

    public static synchronized void shutdown() {
        if (!STARTED.get() || !STOPPED.compareAndSet(false, true)) return;
        LogUtil.info("Bridge shutting down...");
        try { if (beacon != null) beacon.stop(); } catch (Exception e) {}
        try { if (wsServer != null) wsServer.stop(); } catch (Exception e) {}
        try { if (watcher != null) watcher.stop(); } catch (Exception e) {}
    }

    private static File resolveGameDir() {
        try {
            Class<?> c = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object inst = c.getMethod("getInstance").invoke(null);
            return ((java.nio.file.Path) c.getMethod("getGameDir").invoke(inst)).toFile();
        } catch (Throwable ignored) {}
        try {
            // NeoForge 1.20.2+ (net.neoforged.*)
            Class<?> c = Class.forName("net.neoforged.fml.loading.FMLPaths");
            Object gd = c.getField("GAMEDIR").get(null);
            return ((java.nio.file.Path) gd.getClass().getMethod("get").invoke(gd)).toFile();
        } catch (Throwable ignored) {}
        try {
            // Forge 1.20.1+ — FMLLoader.getGamePath() is the public API.
            Class<?> c = Class.forName("net.minecraftforge.fml.loading.FMLLoader");
            return ((java.nio.file.Path) c.getMethod("getGamePath").invoke(null)).toFile();
        } catch (Throwable ignored) {}
        return null;
    }
}
