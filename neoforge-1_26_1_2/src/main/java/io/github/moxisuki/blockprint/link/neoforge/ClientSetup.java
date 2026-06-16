package io.github.moxisuki.blockprint.link.neoforge;

import io.github.moxisuki.blockprint.link.LitematicMod;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * GAME bus events for NeoForge 26.x.
 * F7 opens/closes {@link QrScreen} (mouse freed, no pause).
 */
final class ClientSetup {

    private ClientSetup() {}

    static void register() {
        NeoForge.EVENT_BUS.register(new Object() {
            @SubscribeEvent
            void onTick(ClientTickEvent.Post event) {
                while (UiHooksNeoforge.TOGGLE_KEY != null
                       && UiHooksNeoforge.TOGGLE_KEY.consumeClick()) {
                    var mc = Minecraft.getInstance();
                    if (mc.screen instanceof QrScreen) {
                        mc.setScreen(null);
                    } else if (mc.screen == null && mc.player != null) {
                        mc.setScreen(new QrScreen());
                    }
                }
            }

            @SubscribeEvent
            void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
                LitematicMod.onPlayerJoin(event.getEntity());
            }
        });
    }
}
