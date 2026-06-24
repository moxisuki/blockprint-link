package io.github.moxisuki.blockprint.link.bridge;

import io.github.moxisuki.blockprint.link.LogUtil;

import io.github.moxisuki.blockprint.link.schematic.SchematicScanner;

import java.io.File;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Watches schematics/ + saves/*&#47;structures/ for blueprint file
 * changes and notifies WebSocket subscribers.
 */
public final class SchematicsWatcher {

    private final File gameDir;
    private final File schematicsDir;
    private final CopyOnWriteArrayList<Consumer<List<SchematicScanner.Entry>>> listeners =
        new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<Path> watchedDirs = new HashSet<>();

    private WatchService watchService;
    private Thread watchThread;

    public SchematicsWatcher(File gameDir) {
        this.gameDir = gameDir;
        this.schematicsDir = SchematicScanner.schematicsDir(gameDir);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;

        try {
            if (!schematicsDir.isDirectory()) {
                Files.createDirectories(schematicsDir.toPath());
            }
            watchService = schematicsDir.toPath().getFileSystem().newWatchService();

            // Watch schematics/ + all saves/ directories + existing structure dirs
            registerWatch(schematicsDir.toPath());
            registerSavesRoots();
            registerExistingStructures();
            registerWorldEditSchematics();

            watchThread = new Thread(this::watchLoop, "blockprintlink-watcher");
            watchThread.setDaemon(true);
            watchThread.start();
            LogUtil.info("[BlockPrintLink/Bridge] Watching schematics + saves tree");
        } catch (Exception e) {
            LogUtil.error("[BlockPrintLink/Bridge] WatchService failed: " + e.getMessage());
            running.set(false);
            return;
        }

        rescanAndNotify();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (watchThread != null) watchThread.interrupt();
        if (watchService != null) {
            try { watchService.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Re-probe WorldEdit presence and, if newly detected, register the
     * config/worldedit/schematics leaf dir for live watching. Called
     * from the FMLLoadCompleteEvent handler in each loader's
     * ClientSetup — by that point every mod has finished constructing,
     * so the cached "not loaded" answer from the @Mod constructor
     * (which can run before WE is constructed) gets corrected.
     *
     * Event-driven (one probe per load-complete), no polling. After
     * this call, {@link ModDetection} caches the result for the rest
     * of the session.
     */
    public void recheckWorldEdit() {
        ModDetection.invalidateCache();
        try {
            File weDir = new File(gameDir, "config/worldedit");
            if (!weDir.isDirectory()) return;
            File schemDir = new File(weDir, "schematics");
            if (schemDir.isDirectory() && ModDetection.isWorldEditLoaded()) {
                // registerWatch is idempotent on absolute path, so this is
                // safe to call repeatedly even if WE was already detected
                // at startup.
                registerWatch(schemDir.toPath());
                LogUtil.info("[BlockPrintLink/Bridge] Watching WorldEdit schematics: "
                    + schemDir.getAbsolutePath());
            }
            rescanAndNotify();
        } catch (IOException e) {
            LogUtil.warn("[BlockPrintLink/Bridge] recheckWorldEdit failed: " + e.getMessage());
        }
    }

    public void addListener(Consumer<List<SchematicScanner.Entry>> listener) {
        listeners.add(listener);
    }

    public File getSchematicsDir() {
        return schematicsDir;
    }

    // ── watch registration ──────────────────────────────────────

    private void registerWatch(Path dir) throws IOException {
        if (!watchedDirs.add(dir.normalize().toAbsolutePath())) return;
        if (!Files.isDirectory(dir)) return;
        dir.register(watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_DELETE,
            StandardWatchEventKinds.ENTRY_MODIFY);
    }

    /** Find all saves/ directories this instance might use. */
    private static Set<File> savesRoots(File gameDir) {
        Set<File> roots = new java.util.LinkedHashSet<>();
        // Instance-specific: versions/1.21.1-.../saves/
        roots.add(new File(gameDir, "saves"));
        // Shared: .minecraft/saves/ (version-isolated launchers)
        File p = gameDir.getParentFile();
        while (p != null) {
            File candidate = new File(p, "saves");
            roots.add(candidate);
            // stop when we see both a saves/ and a versions/ sibling
            if (new File(p, "versions").isDirectory()) break;
            p = p.getParentFile();
        }
        return roots;
    }

    /** Watch saves/ roots so new worlds trigger structure registration. */
    private void registerSavesRoots() throws IOException {
        for (File savesRoot : savesRoots(gameDir)) {
            if (savesRoot.isDirectory()) {
                registerWatch(savesRoot.toPath());
            }
        }
    }

    /** Register all currently existing structures/ directories. */
    private void registerExistingStructures() throws IOException {
        for (File savesRoot : savesRoots(gameDir)) {
            if (!savesRoot.isDirectory()) continue;
            File[] worlds = savesRoot.listFiles(File::isDirectory);
            if (worlds == null) continue;
            for (File world : worlds) {
                File sd = new File(world, "generated/minecraft/structures");
                if (sd.isDirectory()) {
                    registerWatch(sd.toPath());
                }
            }
        }
    }

    /**
     * Watch WorldEdit's config/worldedit/schematics directory. We always
     * register the parent {@code config/worldedit/} dir so chain-watch
     * picks up a {@code /schematics/} subdir that WorldEdit creates on
     * its first save — even if WorldEdit isn't detected at startup (it
     * may load later in the mod cycle, after our @Mod constructor runs).
     * The leaf {@code /schematics/} dir is only registered when it
     * already exists, so we don't try to watch a non-existent path.
     */
    private void registerWorldEditSchematics() throws IOException {
        File weDir = new File(gameDir, "config/worldedit");
        if (!weDir.isDirectory()) return;
        registerWatch(weDir.toPath());
        File schemDir = new File(weDir, "schematics");
        if (schemDir.isDirectory() && ModDetection.isWorldEditLoaded()) {
            registerWatch(schemDir.toPath());
            LogUtil.info("[BlockPrintLink/Bridge] Watching WorldEdit schematics: " + schemDir.getAbsolutePath());
        }
    }

    // ── event loop ────────────────────────────────────────────

    private void watchLoop() {
        while (running.get() && watchService != null) {
            WatchKey key;
            try {
                key = watchService.take();  // blocking — no polling
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }

            Path watchedDir = (Path) key.watchable();
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) continue;
                if (kind != StandardWatchEventKinds.ENTRY_CREATE
                    && kind != StandardWatchEventKinds.ENTRY_DELETE
                    && kind != StandardWatchEventKinds.ENTRY_MODIFY) continue;

                Path child = watchedDir.resolve((Path) event.context());

                // Chain-watch: any new directory gets a watch so we
                // can follow saves → world → generated → minecraft →
                // structures until we hit the blueprint leaf dirs.
                if (kind == StandardWatchEventKinds.ENTRY_CREATE
                    && Files.isDirectory(child)) {
                    try { registerWatch(child); } catch (Exception ignored) {}
                }
            }

            key.reset();
            rescanAndNotify();
        }
    }

    private void rescanAndNotify() {
        try {
            List<SchematicScanner.Entry> entries = SchematicScanner.scanAll(gameDir);
            for (var l : listeners) {
                try { l.accept(entries); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }
}
