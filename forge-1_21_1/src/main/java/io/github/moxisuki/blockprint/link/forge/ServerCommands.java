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