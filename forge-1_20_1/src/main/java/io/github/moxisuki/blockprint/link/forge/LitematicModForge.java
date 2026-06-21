package io.github.moxisuki.blockprint.link.forge;

import io.github.moxisuki.blockprint.link.LitematicMod;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;

@Mod(LitematicMod.MOD_ID)
public class LitematicModForge {

    public LitematicModForge() {
        io.github.moxisuki.blockprint.link.bridge.ClientMeta.setMcVersion("1.20.1");
        io.github.moxisuki.blockprint.link.bridge.ClientMeta.setLoader("forge");
        io.github.moxisuki.blockprint.link.bridge.ClientMeta.setLoaderVersion("47.4.10");
        BridgeConfigSpec.register();
        ModLoadingContext.get().registerExtensionPoint(
            ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) ->
                new Screen(Component.literal("BlockPrint Link")) {
                    @Override protected void init() {
                        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE,
                            btn -> mc.setScreen(parent))
                            .pos(this.width / 2 - 75, this.height / 2 + 60).size(150, 20).build());
                    }
                    @Override public void render(GuiGraphics g, int mX, int mY, float pt) {
                        renderBackground(g);
                        g.drawCenteredString(font, Component.literal("§6BlockPrint Link §rConfig"),
                            this.width / 2, 50, 0xFFFFFF);
                        g.drawCenteredString(font, Component.literal(
                            "Edit §econfig/blockprintlink-bridge.toml§r in your game folder"),
                            this.width / 2, this.height / 2 - 20, 0xAAAAAA);
                        super.render(g, mX, mY, pt);
                    }
                })
        );
        LitematicMod.init();
        ClientSetup.register();
    }
}
