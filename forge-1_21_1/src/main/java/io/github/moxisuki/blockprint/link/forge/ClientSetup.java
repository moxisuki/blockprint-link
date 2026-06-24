package io.github.moxisuki.blockprint.link.forge;

import io.github.moxisuki.blockprint.link.LogUtil;
import io.github.moxisuki.blockprint.link.LitematicMod;
import io.github.moxisuki.blockprint.link.bridge.Bg2ClipboardCache;
import io.github.moxisuki.blockprint.link.bridge.BridgeConfig;
import io.github.moxisuki.blockprint.link.bridge.LitematicBridge;
import io.github.moxisuki.blockprint.link.bridge.PlatformHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;

final class ClientSetup {

    private ClientSetup() {}

    static void register(IEventBus modBus) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;

        PlatformHooks.setImpl(new PlatformHooks.Impl() {
            @Override public void toggleQr() {}
            @Override public void registerReloadCommand(Runnable r) {}
            @Override public void sendChatToPlayer(Component text) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.execute(() -> mc.player.displayClientMessage(text, false));
                }
            }
            @Override public ClickEvent makeClickEvent(String command) {
                return new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + command);
            }
            @Override public HoverEvent makeHoverEvent(Component text) {
                return new HoverEvent(HoverEvent.Action.SHOW_TEXT, text);
            }
        });

        // Game events → MinecraftForge.EVENT_BUS
        MinecraftForge.EVENT_BUS.register(new Object() {
            @SubscribeEvent
            public void onTick(TickEvent.ClientTickEvent event) {
                if (event.phase != TickEvent.Phase.END) return;
                while (UiHooksForge.TOGGLE_KEY != null
                       && UiHooksForge.TOGGLE_KEY.consumeClick()) {
                    var mc = Minecraft.getInstance();
                    if (mc.screen instanceof QrScreen)
                        mc.setScreen(null);
                    else if (mc.screen == null && mc.player != null)
                        mc.setScreen(new QrScreen());
                }
                // BG2 clipboard bridge
                String pendingId = Bg2ClipboardCache.consumePendingCopyId();
                if (pendingId != null) {
                    byte[] bytes = Bg2ClipboardCache.take(pendingId);
                    if (bytes != null) {
                        try {
                            Minecraft mc = Minecraft.getInstance();
                            mc.keyboardHandler.setClipboard(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                            if (mc.player != null) {
                                mc.player.displayClientMessage(
                                    Component.translatable("blockprintlink.chat.bg2_copied", bytes.length), false);
                            }
                        } catch (Throwable t) {
                            LogUtil.warn("[BlockPrintLink/Bridge] Clipboard copy failed: " + t);
                        }
                    }
                }
            }

            @SubscribeEvent
            public void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
                if (!BridgeConfig.showChatMessages()) return;
                event.getEntity().displayClientMessage(
                    Component.translatable("blockprintlink.chat.loaded",
                        LitematicMod.MOD_NAME, LitematicMod.MOD_VERSION), false);
                event.getEntity().displayClientMessage(
                    Component.translatable("blockprintlink.chat.token_info",
                        BridgeConfig.sessionToken(), BridgeConfig.hotkeyName()), false);
            }
        });

        modBus.register(new Object() {
            @SubscribeEvent
            public void onLoadComplete(FMLLoadCompleteEvent event) {
                LitematicBridge.recheckWorldEdit();
            }
        });
    }
}