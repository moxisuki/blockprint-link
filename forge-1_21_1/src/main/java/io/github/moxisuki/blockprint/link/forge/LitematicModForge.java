package io.github.moxisuki.blockprint.link.forge;

import io.github.moxisuki.blockprint.link.LitematicMod;
import net.minecraftforge.fml.common.Mod;

@Mod(LitematicMod.MOD_ID)
public class LitematicModForge {

    public LitematicModForge() {
        io.github.moxisuki.blockprint.link.bridge.ClientMeta.setMcVersion("1.21.1");
        io.github.moxisuki.blockprint.link.bridge.ClientMeta.setLoader("forge");
        io.github.moxisuki.blockprint.link.bridge.ClientMeta.setLoaderVersion("52.1.9");
        BridgeConfigSpec.register();
        LitematicMod.init();
        ClientSetup.register();
    }
}
