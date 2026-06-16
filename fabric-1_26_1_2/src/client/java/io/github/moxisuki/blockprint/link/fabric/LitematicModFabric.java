package io.github.moxisuki.blockprint.link.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.moxisuki.blockprint.link.LitematicMod;
import io.github.moxisuki.blockprint.link.bridge.BridgeConfig;
import io.github.moxisuki.blockprint.link.bridge.LitematicBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class LitematicModFabric implements ClientModInitializer {
    public static KeyMapping TOGGLE_KEY;

    @Override
    public void onInitializeClient() {
        io.github.moxisuki.blockprint.link.bridge.ClientMeta.setMcVersion("26.1.2");
        io.github.moxisuki.blockprint.link.bridge.ClientMeta.setLoader("fabric");
        io.github.moxisuki.blockprint.link.bridge.ClientMeta.setLoaderVersion("0.19.3");
        LitematicMod.init();

        // Fabric 26.x: KeyMapping.Category API, vanilla KeyMapping registration
        var category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("blockprintlink", "blockprintlink"));
        TOGGLE_KEY = new KeyMapping(
            "key.blockprintlink.toggle_bridge_qr",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F7,
            category
        );
        BridgeConfig.setHotkeyNameSupplier(() ->
            TOGGLE_KEY.getTranslatedKeyMessage().getString());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_KEY != null && TOGGLE_KEY.consumeClick()) {
                var mc = Minecraft.getInstance();
                if (mc.screen instanceof QrScreen)
                    mc.setScreen(null);
                else if (mc.screen == null && mc.player != null)
                    mc.setScreen(new QrScreen());
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> LitematicBridge.shutdown());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!BridgeConfig.showChatMessages()) return;
            if (client.player != null) {
                client.player.sendSystemMessage(
                    Component.translatable("blockprintlink.chat.loaded", "BlockPrint Link", "0.1.0"));
                client.player.sendSystemMessage(
                    Component.translatable("blockprintlink.chat.token_info",
                        BridgeConfig.sessionToken(), BridgeConfig.hotkeyName()));
            }
        });
    }
}
