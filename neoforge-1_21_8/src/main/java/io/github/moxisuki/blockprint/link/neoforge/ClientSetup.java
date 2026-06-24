package io.github.moxisuki.blockprint.link.neoforge;

import io.github.moxisuki.blockprint.link.LitematicMod;
import io.github.moxisuki.blockprint.link.bridge.LitematicBridge;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

final class ClientSetup {

    private ClientSetup() {}

    static void register(IEventBus modBus) {
        if (net.neoforged.fml.loading.FMLEnvironment.dist != net.neoforged.api.distmarker.Dist.CLIENT) return;

        NeoForge.EVENT_BUS.register(new Object() {
            @SubscribeEvent
            void onTick(ClientTickEvent.Post event) {
                while (UiHooksNeoforge.TOGGLE_KEY != null
                       && UiHooksNeoforge.TOGGLE_KEY.consumeClick()) {
                    var mc = Minecraft.getInstance();
                    if (mc.screen instanceof QrScreen)
                        mc.setScreen(null);
                    else if (mc.screen == null && mc.player != null)
                        mc.setScreen(new QrScreen());
                }
            }

            @SubscribeEvent
            void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
                LitematicMod.onPlayerJoin(event.getEntity());
            }
        });

        modBus.register(new Object() {
            @SubscribeEvent
            void onLoadComplete(FMLLoadCompleteEvent event) {
                LitematicBridge.recheckWorldEdit();
            }
        });
    }
}
