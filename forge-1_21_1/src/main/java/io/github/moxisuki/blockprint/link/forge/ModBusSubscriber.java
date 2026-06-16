package io.github.moxisuki.blockprint.link.forge;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.moxisuki.blockprint.link.LitematicMod;
import io.github.moxisuki.blockprint.link.bridge.BridgeConfig;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = LitematicMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ModBusSubscriber {

    private ModBusSubscriber() {}

    @SubscribeEvent
    static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        UiHooksForge.TOGGLE_KEY = new KeyMapping(
            "key.blockprintlink.toggle_bridge_qr",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            UiHooksForge.hotkeyCode(),
            "key.categories.blockprintlink"
        );
        event.register(UiHooksForge.TOGGLE_KEY);
        BridgeConfig.setHotkeyNameSupplier(() ->
            UiHooksForge.TOGGLE_KEY.getTranslatedKeyMessage().getString());
    }
}
