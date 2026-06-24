package io.github.moxisuki.blockprint.link.fabric;

import io.github.moxisuki.blockprint.link.LitematicMod;
import io.github.moxisuki.blockprint.link.bridge.BridgeConfig;
import io.github.moxisuki.blockprint.link.bridge.LitematicBridge;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class LitematicModFabric implements ClientModInitializer {
    public static KeyMapping TOGGLE_KEY;

    @Override
    public void onInitializeClient() {
        io.github.moxisuki.blockprint.link.bridge.ClientMeta.setMcVersion("1.21.4");
        io.github.moxisuki.blockprint.link.bridge.ClientMeta.setLoader("fabric");
        LitematicMod.init();

        TOGGLE_KEY = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.blockprintlink.toggle_bridge_qr",
            InputConstants.Type.KEYSYM,
            UiHooksFabric.hotkeyCode(),
            "key.categories.blockprintlink"
        ));
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
                client.player.displayClientMessage(
                    Component.translatable("blockprintlink.chat.loaded",
                        LitematicMod.MOD_NAME, LitematicMod.MOD_VERSION), false);
                client.player.displayClientMessage(
                    Component.translatable("blockprintlink.chat.token_info",
                        BridgeConfig.sessionToken(), BridgeConfig.hotkeyName()), false);
            }
        });
    }
}
