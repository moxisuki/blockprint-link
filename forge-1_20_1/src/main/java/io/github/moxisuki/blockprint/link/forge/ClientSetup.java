package io.github.moxisuki.blockprint.link.forge;

import io.github.moxisuki.blockprint.link.LitematicMod;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;

final class ClientSetup {

    // Edge detection: track previous tick's key state.
    private static boolean lastKeyState = false;
    // Track whether our QrScreen is open, so we know to close it
    // even if the screen closed itself before this tick fires.
    private static boolean qrScreenOpen = false;

    private ClientSetup() {}

    static void register() {
        if (FMLEnvironment.dist != Dist.CLIENT) return;

        MinecraftForge.EVENT_BUS.register(new Object() {
            @SubscribeEvent
            public void onTick(TickEvent.ClientTickEvent event) {
                if (event.phase != TickEvent.Phase.END) return;
                if (UiHooksForge.TOGGLE_KEY == null) return;
                var mc = Minecraft.getInstance();

                // Detect if QrScreen was closed externally (e.g. ESC)
                if (qrScreenOpen && !(mc.screen instanceof QrScreen)) {
                    qrScreenOpen = false;
                }

                // 1.20.1: consumeClick() does not exist in KeyMapping.
                // Use isDown() with edge detection instead.
                boolean down = UiHooksForge.TOGGLE_KEY.isDown();
                if (down && !lastKeyState) {
                    if (qrScreenOpen) {
                        mc.setScreen(null);
                        qrScreenOpen = false;
                    } else if (mc.screen == null && mc.player != null) {
                        mc.setScreen(new QrScreen());
                        qrScreenOpen = true;
                    }
                }
                lastKeyState = down;
            }

            @SubscribeEvent
            public void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
                if (!io.github.moxisuki.blockprint.link.bridge.BridgeConfig.showChatMessages()) return;
                event.getEntity().displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("blockprintlink.chat.loaded",
                        "BlockPrint Link", "0.1.0"), false);
                event.getEntity().displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("blockprintlink.chat.token_info",
                        io.github.moxisuki.blockprint.link.bridge.BridgeConfig.sessionToken(),
                        io.github.moxisuki.blockprint.link.bridge.BridgeConfig.hotkeyName()), false);
            }
        });
    }
}
