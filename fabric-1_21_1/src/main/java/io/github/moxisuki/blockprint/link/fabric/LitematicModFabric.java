package io.github.moxisuki.blockprint.link.fabric;

import io.github.moxisuki.blockprint.link.LitematicMod;
import io.github.moxisuki.blockprint.link.bridge.Bg2ClipboardCache;
import io.github.moxisuki.blockprint.link.bridge.BridgeConfig;
import io.github.moxisuki.blockprint.link.bridge.LitematicBridge;
import io.github.moxisuki.blockprint.link.bridge.PlatformHooks;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

public class LitematicModFabric implements ClientModInitializer {
    public static KeyMapping TOGGLE_KEY;

    @Override
    public void onInitializeClient() {
        io.github.moxisuki.blockprint.link.bridge.ClientMeta.setMcVersion("1.21.1");
        io.github.moxisuki.blockprint.link.bridge.ClientMeta.setLoader("fabric");
        LitematicMod.init();

        PlatformHooks.setImpl(new PlatformHooks.Impl() {
            @Override public void toggleQr() {}
            @Override public void registerReloadCommand(Runnable r) {}
            @Override public void sendChatToPlayer(Component text) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.execute(() -> mc.player.displayClientMessage(text, false));
                }
            }
            @Override public ClickEvent makeClickEvent(String command) { return null; }
            @Override public HoverEvent makeHoverEvent(Component text) { return null; }
        });

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

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            Bg2ClipboardCache.clear();
            LitematicBridge.shutdown();
        });
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

        // Client commands: /blockprint-reload + /blockprintlink copy-bg2
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("blockprint-reload").executes(ctx -> {
                Minecraft.getInstance().player.displayClientMessage(
                    Component.literal("§aLitematic Bridge config reloaded."), false);
                return 1;
            }));
            dispatcher.register(ClientCommandManager.literal("blockprintlink")
                .then(ClientCommandManager.literal("copy-bg2")
                    .then(ClientCommandManager.argument("id", StringArgumentType.string())
                        .executes(ctx -> {
                            String id = ctx.getArgument("id", String.class);
                            byte[] bytes = Bg2ClipboardCache.take(id);
                            if (bytes == null) {
                                Minecraft.getInstance().player.displayClientMessage(
                                    Component.literal("§c[BlockPrint] §fCache miss for id " + id
                                        + " (already copied, expired, or unknown)"), false);
                                return 0;
                            }
                            try {
                                Minecraft mc = Minecraft.getInstance();
                                mc.keyboardHandler.setClipboard(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                                final int size = bytes.length;
                                mc.player.displayClientMessage(
                                    Component.translatable("blockprintlink.chat.bg2_copied", size), false);
                            } catch (Throwable t) {
                                Minecraft.getInstance().player.displayClientMessage(
                                    Component.literal("§c[BlockPrint] §fClipboard failed: " + t), false);
                            }
                            return 1;
                        })
                    )
                ));
        });
    }
}