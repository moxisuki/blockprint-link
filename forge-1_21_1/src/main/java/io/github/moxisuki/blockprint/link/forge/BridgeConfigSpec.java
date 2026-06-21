package io.github.moxisuki.blockprint.link.forge;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.moxisuki.blockprint.link.LitematicMod;
import io.github.moxisuki.blockprint.link.bridge.BridgeConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * Forge 1.21.1 configuration spec. Registers via
 * {@link ModLoadingContext#get()} as {@code CLIENT} type.
 */
@Mod.EventBusSubscriber(modid = LitematicMod.MOD_ID, value = Dist.CLIENT)
public final class BridgeConfigSpec {

    static final ForgeConfigSpec SPEC;
    static final ForgeConfigSpec.ConfigValue<String> TOKEN;
    static final ForgeConfigSpec.BooleanValue SHOW_CHAT;
    static final ForgeConfigSpec.IntValue WS_PORT;
    static final ForgeConfigSpec.IntValue DISCOVERY_PORT;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("bridge");
        TOKEN = b.comment("Session token. Empty = auto-generate.").define("token", "");
        SHOW_CHAT = b.comment("Show bridge status in chat").define("showChatMessages", true);
        WS_PORT = b.comment("WebSocket port").defineInRange("wsPort", 18080, 1024, 65535);
        DISCOVERY_PORT = b.comment("UDP beacon port").defineInRange("discoveryPort", 18081, 1024, 65535);
        b.pop();
        SPEC = b.build();
    }

    /** Called from LitematicModForge's constructor. */
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, SPEC);
    }

    public static void syncToRuntime() {
        BridgeConfig.applyValues(TOKEN.get(), SHOW_CHAT.get(), WS_PORT.get(), DISCOVERY_PORT.get());
    }

    @SubscribeEvent
    static void onLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) syncToRuntime();
    }

    @SubscribeEvent
    static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) syncToRuntime();
    }

    private BridgeConfigSpec() {}
}
