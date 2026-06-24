package io.github.moxisuki.blockprint.link.neoforge;

import io.github.moxisuki.blockprint.link.LitematicMod;
import io.github.moxisuki.blockprint.link.bridge.LitematicBridge;
import io.github.moxisuki.blockprint.link.bridge.PlatformHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * GAME bus events for NeoForge 26.x.
 * F7 opens/closes {@link QrScreen} (mouse freed, no pause).
 */
final class ClientSetup {

    private ClientSetup() {}

    static void register(IEventBus modBus) {
        // Typed ClickEvent/HoverEvent — avoids reflection which fails on
        // 26.x because the old constructors don't exist and the static
        // factory method names differ from what 1.21.1's reflection expects.
        PlatformHooks.setImpl(new PlatformHooks.Impl() {
            @Override public void toggleQr() {}
            @Override public void registerReloadCommand(Runnable r) {}
            @Override public void sendChatToPlayer(Component text) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.execute(() -> mc.player.sendSystemMessage(text));
                }
            }
            @Override public ClickEvent makeClickEvent(String command) {
                // 26.x sealed interface: new ClickEvent.RunCommand(value)
                return new ClickEvent.RunCommand("/" + command);
            }
            @Override public HoverEvent makeHoverEvent(Component text) {
                // 26.x sealed interface: new HoverEvent.ShowText(text)
                return new HoverEvent.ShowText(text);
            }
        });

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

        modBus.register(new Object() {
            @SubscribeEvent
            void onLoadComplete(FMLLoadCompleteEvent event) {
                LitematicBridge.recheckWorldEdit();
            }
        });
    }

    }