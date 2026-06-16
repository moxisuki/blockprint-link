package io.github.moxisuki.blockprint.link.neoforge;

import io.github.moxisuki.blockprint.link.LitematicMod;
import io.github.moxisuki.blockprint.link.bridge.BridgeConfig;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@EventBusSubscriber(modid = LitematicMod.MOD_ID, value = Dist.CLIENT)
public final class ModBusSubscriber {

    private static final KeyMapping.Category CATEGORY =
        new KeyMapping.Category(Identifier.fromNamespaceAndPath("blockprintlink", "blockprintlink"));

    private ModBusSubscriber() {}

    @SubscribeEvent
    static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        UiHooksNeoforge.TOGGLE_KEY = new KeyMapping(
            "key.blockprintlink.toggle_bridge_qr",
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
            UiHooksNeoforge.hotkeyCode(),
            CATEGORY
        );
        event.register(UiHooksNeoforge.TOGGLE_KEY);
        BridgeConfig.setHotkeyNameSupplier(() ->
            UiHooksNeoforge.TOGGLE_KEY.getTranslatedKeyMessage().getString());
    }
}
