package io.github.moxisuki.blockprint.link.neoforge;

import io.github.moxisuki.blockprint.link.LitematicMod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(LitematicMod.MOD_ID)
public class LitematicModNeoForge {

    public LitematicModNeoForge(IEventBus modBus, ModContainer container) {
        BridgeConfigSpec.register(container);
        LitematicMod.init();
        ClientSetup.register(modBus);
    }
}
