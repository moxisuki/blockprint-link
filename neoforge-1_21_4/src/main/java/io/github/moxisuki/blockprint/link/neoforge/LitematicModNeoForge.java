package io.github.moxisuki.blockprint.link.neoforge;

import io.github.moxisuki.blockprint.link.LitematicMod;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

@Mod(LitematicMod.MOD_ID)
public class LitematicModNeoForge {

    public LitematicModNeoForge() {
        // Config MUST be registered and synced before bridge starts, so the
        // bridge reads the persisted token from TOML, not the transient one.
        ModList.get().getModContainerById(LitematicMod.MOD_ID)
            .ifPresent(BridgeConfigSpec::register);
        LitematicMod.init();
        ClientSetup.register();
    }
}
