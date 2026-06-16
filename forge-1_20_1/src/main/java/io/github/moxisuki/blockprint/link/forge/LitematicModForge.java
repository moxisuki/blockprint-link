package io.github.moxisuki.blockprint.link.forge;

import io.github.moxisuki.blockprint.link.LitematicMod;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * Forge 1.20.1 mod entry point. Mirror of the NeoForge entry but with
 * {@code net.minecraftforge.fml.common.Mod} and the legacy FML mod
 * loading context.
 */
@Mod(LitematicMod.MOD_ID)
public class LitematicModForge {

    public LitematicModForge() {
        // Inject metadata BEFORE init — the SRG-named runtime can't
        // resolve Mojang method names via reflection.
        io.github.moxisuki.blockprint.link.bridge.ClientMeta.setMcVersion("1.20.1");
        io.github.moxisuki.blockprint.link.bridge.ClientMeta.setLoader("forge");
        io.github.moxisuki.blockprint.link.bridge.ClientMeta.setLoaderVersion("47.4.10");
        // Forge config is registered via ModLoadingContext.
        BridgeConfigSpec.registerOnContext();
        LitematicMod.init();
        ClientSetup.register();
    }
}
