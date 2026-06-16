package io.github.moxisuki.blockprint.link;

import io.github.moxisuki.blockprint.link.bridge.BridgeConfig;
import io.github.moxisuki.blockprint.link.bridge.LitematicBridge;
import net.minecraft.network.chat.Component;

/**
 * Cross-platform core. The platform subprojects call {@link #init()} from
 * their respective mod entry points; that wires up the WebSocket bridge
 * and (in {@link #onPlayerJoin}) shows the schematics list and bridge
 * token directly in the player's chat.
 *
 * <p>No dependency on Architectury — common stays portable. The
 * platform-specific event registration lives in the fabric/neoforge
 * subprojects, which call {@link #onPlayerJoin(Object)} on the right
 * event.
 */
public class LitematicMod {
    public static final String MOD_ID = "blockprintlink";
    public static final String MOD_NAME = "BlockPrint Link";
    public static final String MOD_VERSION = "0.1.0";

    public static void init() {
        LitematicBridge.start();
    }

    /**
     * Called from each platform's player-join hook. Argument is the
     * platform's Player type (ServerPlayer on both Fabric and NeoForge 1.21).
     * We only call methods that are present on both.
     */
    public static void onPlayerJoin(Object player) {
        if (!BridgeConfig.showChatMessages()) return;

        sendMessage(player, Component.translatable("blockprintlink.chat.loaded", MOD_NAME, MOD_VERSION));
        sendMessage(player, Component.translatable(
            "blockprintlink.chat.token_info",
            BridgeConfig.sessionToken(),
            BridgeConfig.hotkeyName()));
    }

    private static void sendMessage(Object player, Component text) {
        // Chain of reflection attempts — each newer MC version renames
        // the chat delivery method. Order: newest first (fails fast),
        // oldest last (compatibility fallback).
        // 26.x:  sendSystemMessage(Component, boolean)
        // 26.x:  sendSystemMessage(Component)
        // 1.19.3+: displayClientMessage(Component, boolean)
        // pre-1.19.3: displayClientMessage(Component)
        try {
            player.getClass().getMethod("sendSystemMessage", Component.class, boolean.class)
                .invoke(player, text, false);
        } catch (Throwable t1) {
            try {
                player.getClass().getMethod("sendSystemMessage", Component.class)
                    .invoke(player, text);
            } catch (Throwable t2) {
                try {
                    player.getClass().getMethod("displayClientMessage", Component.class, boolean.class)
                        .invoke(player, text, false);
                } catch (Throwable t3) {
                    try {
                        player.getClass().getMethod("displayClientMessage", Component.class)
                            .invoke(player, text);
                    } catch (Throwable t4) { /* silent */ }
                }
            }
        }
    }
}
