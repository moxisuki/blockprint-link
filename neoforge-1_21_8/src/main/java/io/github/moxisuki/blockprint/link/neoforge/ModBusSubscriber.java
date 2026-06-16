package io.github.moxisuki.blockprint.link.neoforge;

import io.github.moxisuki.blockprint.link.LitematicMod;
import io.github.moxisuki.blockprint.link.bridge.BridgeConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * MOD bus subscriber for events that fire before the @Mod constructor
 * runs. Keybinding registration must happen here because
 * {@link RegisterKeyMappingsEvent} is dispatched during mod construction,
 * and registering via {@code modBus.addListener} in the constructor may
 * arrive too late.
 */
@EventBusSubscriber(modid = LitematicMod.MOD_ID, value = Dist.CLIENT)
public final class ModBusSubscriber {

    private ModBusSubscriber() {}

    @SubscribeEvent
    static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        UiHooksNeoforge.TOGGLE_KEY = new KeyMapping(
            "key.blockprintlink.toggle_bridge_qr",
            InputConstants.Type.KEYSYM,
            UiHooksNeoforge.hotkeyCode(),
            "category.blockprintlink"
        );
        event.register(UiHooksNeoforge.TOGGLE_KEY);
        BridgeConfig.setHotkeyNameSupplier(() ->
            UiHooksNeoforge.TOGGLE_KEY.getTranslatedKeyMessage().getString());
    }
}
