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

    private ClientSetup() {}

    static void register() {
        if (FMLEnvironment.dist != Dist.CLIENT) return;

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
            }

            @SubscribeEvent
            public void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
                if (!io.github.moxisuki.blockprint.link.bridge.BridgeConfig.showChatMessages()) return;
                // Compiled call (not reflection) — reobfJar remaps method names to SRG
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
