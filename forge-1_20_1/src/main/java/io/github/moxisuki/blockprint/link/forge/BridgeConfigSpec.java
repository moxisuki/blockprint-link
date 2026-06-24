package io.github.moxisuki.blockprint.link.forge;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.github.moxisuki.blockprint.link.LitematicMod;
import io.github.moxisuki.blockprint.link.bridge.Bg2ClipboardCache;
import io.github.moxisuki.blockprint.link.bridge.BridgeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * Forge 1.20.1 configuration spec. Registers via
 * {@link ModLoadingContext#get()} so the config appears
 * in the Forge Mods → Config screen.
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

    @SubscribeEvent
    static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            LiteralArgumentBuilder.<CommandSourceStack>literal("blockprint-reload")
                .executes(ctx -> {
                    syncToRuntime();
                    ctx.getSource().sendSuccess(
                        () -> Component.literal("§aLitematic Bridge config reloaded."), false);
                    return 1;
                })
        );
        event.getDispatcher().register(
            LiteralArgumentBuilder.<CommandSourceStack>literal("blockprintlink")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("copy-bg2")
                    .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("id", StringArgumentType.string())
                        .executes(ctx -> {
                            String id = ctx.getArgument("id", String.class);
                            byte[] bytes = Bg2ClipboardCache.take(id);
                            if (bytes == null) {
                                ctx.getSource().sendSuccess(
                                    () -> Component.literal("§c[BlockPrint] §fCache miss for id " + id
                                        + " (already copied, expired, or unknown)"), false);
                                return 0;
                            }
                            try {
                                Minecraft mc = Minecraft.getInstance();
                                mc.keyboardHandler.setClipboard(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                                final int size = bytes.length;
                                ctx.getSource().sendSuccess(
                                    () -> Component.translatable("blockprintlink.chat.bg2_copied", size), false);
                            } catch (Throwable t) {
                                ctx.getSource().sendSuccess(
                                    () -> Component.literal("§c[BlockPrint] §fClipboard failed: " + t), false);
                            }
                            return 1;
                        })
                    )
                )
        );
    }

    private BridgeConfigSpec() {}
}