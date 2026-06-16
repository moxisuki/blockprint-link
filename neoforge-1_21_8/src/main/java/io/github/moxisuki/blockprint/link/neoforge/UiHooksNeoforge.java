package io.github.moxisuki.blockprint.link.neoforge;

import io.github.moxisuki.blockprint.link.bridge.BridgeConfig;
import net.minecraft.client.KeyMapping;

/**
 * Holds the NeoForge-side hotkey reference. We need a class-level
 * field because {@code RegisterKeyMappingsEvent} is a static event —
 * the registration callback can't easily pass a reference to a
 * per-instance field.
 *
 * <p>Resolution of the GLFW code itself is delegated to
 * {@link BridgeConfig#hotkeyCode()} so the chat banner, the QR overlay
 * label, and the actual keybinding all stay in lockstep.
 */
public final class UiHooksNeoforge {
    public static KeyMapping TOGGLE_KEY;

    private UiHooksNeoforge() {}

    public static int hotkeyCode() {
        return BridgeConfig.hotkeyCode();
    }
}
