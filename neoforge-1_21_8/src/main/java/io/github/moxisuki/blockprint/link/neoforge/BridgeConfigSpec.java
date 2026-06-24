package io.github.moxisuki.blockprint.link.neoforge;

import io.github.moxisuki.blockprint.link.LitematicMod;
import io.github.moxisuki.blockprint.link.bridge.Bg2ClipboardCache;
import io.github.moxisuki.blockprint.link.bridge.BridgeConfig;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

@EventBusSubscriber(modid = LitematicMod.MOD_ID, value = net.neoforged.api.distmarker.Dist.CLIENT)
public final class BridgeConfigSpec {

    static final ModConfigSpec SPEC;
    static final ModConfigSpec.ConfigValue<String> TOKEN;
    static final ModConfigSpec.BooleanValue SHOW_CHAT;
    static final ModConfigSpec.IntValue WS_PORT;
    static final ModConfigSpec.IntValue DISCOVERY_PORT;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("bridge");
        TOKEN = b.comment("Session token. Empty = auto-generate.").define("token", "");
        SHOW_CHAT = b.comment("Show bridge status in chat").define("showChatMessages", true);
        WS_PORT = b.comment("WebSocket port").defineInRange("wsPort", 18080, 1024, 65535);
        DISCOVERY_PORT = b.comment("UDP beacon port").defineInRange("discoveryPort", 18081, 1024, 65535);
        b.pop();
        SPEC = b.build();
    }

    static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, SPEC, "blockprintlink-bridge.toml");
    }

    private static void syncToRuntime() {
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
        // /blockprintlink copy-bg2 <id> — copies the JSON bytes stashed
        // by the BG2 click-to-copy chat flow into the player's clipboard.
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
