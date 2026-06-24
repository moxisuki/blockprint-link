package io.github.moxisuki.blockprint.link.bridge;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

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

    /** True if a platform registered a non-noop implementation. */
    public static boolean hasImpl() {
        return !(impl instanceof NoopImpl);
    }

    /**
     * Send a chat Component to the current client player. Called from
     * {@code LitematicMod.broadcastComponentToCurrentPlayer} (which runs
     * on the render thread via {@code Minecraft.execute(...)}). Each loader
     * implements this with a typed method call — reflection fails on
     * obfuscated jars (e.g. Forge 1.20.1 SRG names).
     */
    public static void sendChatToPlayer(Component text) {
        impl.sendChatToPlayer(text);
    }

    /** Typed ClickEvent construction — avoids reflection which breaks on SRG. */
    public static ClickEvent makeClickEvent(String command) {
        return impl.makeClickEvent(command);
    }

    /** Typed HoverEvent construction — avoids reflection. */
    public static HoverEvent makeHoverEvent(Component text) {
        return impl.makeHoverEvent(text);
    }

    // ── Implementation contract ──

    public interface Impl {
        void toggleQr();
        void registerReloadCommand(Runnable reloader);
        void sendChatToPlayer(Component text);
        ClickEvent makeClickEvent(String command);
        HoverEvent makeHoverEvent(Component text);
    }

    private static final class NoopImpl implements Impl {
        @Override public void toggleQr() {}
        @Override public void registerReloadCommand(Runnable r) {}
        @Override public void sendChatToPlayer(Component text) {}
        @Override public ClickEvent makeClickEvent(String command) { return null; }
        @Override public HoverEvent makeHoverEvent(Component text) { return null; }
    }
}
