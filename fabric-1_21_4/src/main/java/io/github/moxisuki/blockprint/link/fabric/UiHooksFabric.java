package io.github.moxisuki.blockprint.link.fabric;

import io.github.moxisuki.blockprint.link.bridge.BridgeConfig;

/**
 * Platform-specific helpers for the QR overlay hotkey. The actual
 * resolution lives in {@link BridgeConfig#hotkeyCode()} so the chat
 * message and the HUD can show the same value.
 */
public final class UiHooksFabric {
    private UiHooksFabric() {}

    public static int hotkeyCode() {
        return BridgeConfig.hotkeyCode();
    }
}
