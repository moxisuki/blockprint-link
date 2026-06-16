package io.github.moxisuki.blockprint.link.bridge;

/**
 * Abstract platform glue. Each loader subproject (fabric-1_21_1,
 * neoforge-1_21_1, forge-1_20_1, …) injects a concrete implementation
 * during mod construction. Common code calls the static methods; the
 * implementation handles the loader-specific event buses and rendering
 * APIs.
 */
public final class PlatformHooks {
    private static Impl impl = new NoopImpl();

    private PlatformHooks() {}

    /** Set by the platform subproject during mod init. */
    public static void setImpl(Impl i) {
        impl = i != null ? i : new NoopImpl();
    }

    // ── APIs called by common code ──

    public static void toggleQr() {
        impl.toggleQr();
    }

    public static void registerReloadCommand(Runnable reloader) {
        impl.registerReloadCommand(reloader);
    }

    // ── Implementation contract ──

    public interface Impl {
        void toggleQr();
        void registerReloadCommand(Runnable reloader);
    }

    private static final class NoopImpl implements Impl {
        @Override public void toggleQr() {}
        @Override public void registerReloadCommand(Runnable r) {}
    }
}
