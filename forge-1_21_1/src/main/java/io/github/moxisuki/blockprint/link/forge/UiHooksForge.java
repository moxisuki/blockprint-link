package io.github.moxisuki.blockprint.link.forge;

import net.minecraft.client.KeyMapping;

/**
 * Holds the Forge 1.20.1 hotkey reference. {@code RegisterKeyMappingsEvent}
 * is a static event — the registration callback can't easily pass a
 * reference to a per-instance field, so the {@code KeyMapping} is kept
 * in a static and re-read on every game tick.
 */
public final class UiHooksForge {
    public static KeyMapping TOGGLE_KEY;

    private UiHooksForge() {}

    public static int hotkeyCode() {
        return io.github.moxisuki.blockprint.link.bridge.BridgeConfig.hotkeyCode();
    }
}
