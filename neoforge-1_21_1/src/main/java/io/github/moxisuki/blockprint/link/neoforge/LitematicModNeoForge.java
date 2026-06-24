package io.github.moxisuki.blockprint.link.neoforge;

import io.github.moxisuki.blockprint.link.LitematicMod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

@Mod(LitematicMod.MOD_ID)
public class LitematicModNeoForge {

    public LitematicModNeoForge(IEventBus modBus, ModContainer container) {
        // Config MUST be registered and synced before bridge starts, so the
        // bridge reads the persisted token from TOML, not the transient one.
        BridgeConfigSpec.register(container);
        LitematicMod.init();
        // FMLLoadCompleteEvent lives on the mod bus — pass it through.
        ClientSetup.register(modBus);
    }
}
