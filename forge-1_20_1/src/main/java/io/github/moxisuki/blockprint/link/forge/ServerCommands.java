package io.github.moxisuki.blockprint.link.forge;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import io.github.moxisuki.blockprint.link.LitematicMod;
import io.github.moxisuki.blockprint.link.bridge.Bg2ClipboardCache;
import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Server-side stub for the BG2 click-to-copy flow on Forge 1.20.1.
 *
 * <p>{@code RUN_COMMAND} click events route through the server command
 * dispatcher, which doesn't see client-registered commands. This class
 * registers a thin server-side handler that sets a pending copy id in
 * {@link Bg2ClipboardCache}; the client tick handler (in
 * {@link ClientSetup}) picks it up and does the actual clipboard write.
 */
@Mod.EventBusSubscriber(modid = LitematicMod.MOD_ID)
public final class ServerCommands {

    private ServerCommands() {}

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            LiteralArgumentBuilder.<CommandSourceStack>literal("blockprintlink")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("copy-bg2")
                    .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("id", StringArgumentType.string())
                        .executes(ctx -> {
                            Bg2ClipboardCache.setPendingCopy(ctx.getArgument("id", String.class));
                            return 1;
                        }))));
    }
}