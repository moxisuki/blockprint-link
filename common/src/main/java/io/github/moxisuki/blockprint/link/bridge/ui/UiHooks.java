package io.github.moxisuki.blockprint.link.bridge.ui;

import io.github.moxisuki.blockprint.link.LogUtil;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Common-side entry point for the QR HUD overlay. The actual per-frame
 * hook is registered by each platform's UiHooksImpl because Architectury
 * doesn't have a single key-binding + HUD render API that works on both
 * Fabric and NeoForge 1.21.
 *
 * <p>The hotkey is F7 (configurable via {@code bridge_hotkey} in
 * gradle.properties). The default is hidden — the player presses the
 * key to reveal the QR.
 */
public final class UiHooks {

    private UiHooks() {}

    /** The platform-specific UI hook calls this every frame. */
    public static void onRenderHud(GuiGraphics graphics) {
        QrHudRenderer.render(graphics);
    }

    /** Toggle visibility. Called by the platform hotkey handler. */
    public static void toggle() {
        QrHudRenderer.toggle();
    }

    public static void show() { QrHudRenderer.setVisible(true); }
    public static void hide() { QrHudRenderer.setVisible(false); }
    public static boolean isVisible() { return QrHudRenderer.isVisible(); }

    /**
     * Called from each platform's mod init to register the F7 (or
     * configured) hotkey and the HUD render hook. Idempotent.
     */
    public static void register() {
        // The platform's UiHooksImpl does the actual registration. We
        // just exist so that LitematicBridge can call a single entry
        // point without knowing which platform it's on.
        LogUtil.info("[BlockPrintLink/Bridge] UiHooks.register() — platform-specific registration happens in each loader's UiHooksImpl");
    }
}
