package io.github.moxisuki.blockprint.link.fabric;

import io.github.moxisuki.blockprint.link.bridge.BridgeConfig;
import io.github.moxisuki.blockprint.link.bridge.ui.QrData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class QrScreen extends Screen {
    private static final ItemStack ICON_IRON    = new ItemStack(Items.IRON_INGOT);
    private static final ItemStack ICON_GOLD    = new ItemStack(Items.GOLD_INGOT);
    private static final ItemStack ICON_DIAMOND = new ItemStack(Items.DIAMOND);

    private int qrX, qrY, tokenX, tokenY, tokenW;
    private boolean hoverCopy;

    public QrScreen() {
        super(Component.translatable("blockprintlink.ui.title"));
        QrData.ensure();
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int bound = BridgeConfig.hotkeyCode();
        if (keyCode == 256 || keyCode == bound) { onClose(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && hoverCopy && minecraft != null) {
            String url = QrData.connectUrl();
            if (url != null) {
                minecraft.keyboardHandler.setClipboard(url);
                if (minecraft.player != null)
                    minecraft.player.displayClientMessage(
                        Component.translatable("blockprintlink.ui.copied"), false);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override public void onClose() { if (minecraft != null) minecraft.setScreen(null); }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        int px = (width  - QrData.PANEL_W) / 2;
        int py = (height - QrData.PANEL_H) / 2;
        int bx = px + 5, by = py + 5;
        int bw = QrData.PANEL_W - 10, bh = QrData.PANEL_H - 10;

        g.fill(0, 0, width, height, QrData.C_BG);

        g.fill(px, py, px + QrData.PANEL_W, py + QrData.PANEL_H, QrData.C_BORDER_D);
        g.fill(px + 4, py + 4, px + QrData.PANEL_W - 4, py + QrData.PANEL_H - 4, QrData.C_BORDER_L);
        g.fill(bx, by, bx + bw, by + bh, QrData.C_PANEL);

        g.fill(bx, by, bx + bw, by + 16, QrData.C_ACCENT);
        if (font != null) {
            g.drawString(font, Component.translatable("blockprintlink.ui.title"),
                bx + 8, by + 4, 0xFFFFFFFF, true);
            String closeKey = BridgeConfig.hotkeyName();
            String closeHint = closeKey + " / ESC";
            g.drawString(font, closeHint, bx + bw - font.width(closeHint) - 6, by + 4, 0xFFAAAAAA, false);
        }

        qrX = px + (QrData.PANEL_W - QrData.QR_SIZE) / 2;
        qrY = by + 22;
        int[] qr = QrData.pixels();
        if (qr != null) {
            int qw = QrData.qrW(), qh = QrData.qrH();
            slotBorder(g, qrX - 3, qrY - 3, QrData.QR_SIZE + 6, QrData.QR_SIZE + 6);
            g.fill(qrX, qrY, qrX + QrData.QR_SIZE, qrY + QrData.QR_SIZE, 0xFFFFFFFF);
            for (int y = 0; y < qh; y++) {
                int run = -1;
                for (int x = 0; x <= qw; x++) {
                    boolean black = x < qw && (qr[y * qw + x] & 0x00FFFFFF) == 0;
                    if (black && run < 0) run = x;
                    else if (!black && run >= 0) {
                        g.hLine(qrX + run, qrX + x - 1, qrY + y, 0xFF000000);
                        run = -1;
                    }
                }
            }
        }

        if (font == null) return;

        int iy = qrY + QrData.QR_SIZE + 16;
        int ix = bx + 14, rh = QrData.ROW_H;
        g.fill(bx + 4, iy - 4, bx + bw - 4, iy + rh * 3 + 6, 0x18000000);

        g.renderFakeItem(ICON_IRON,    ix, iy);
        g.renderFakeItem(ICON_GOLD,    ix, iy + rh);
        g.renderFakeItem(ICON_DIAMOND, ix, iy + rh * 2);
        String ipS   = QrData.clean(I18n.get("blockprintlink.overlay.ip",   QrData.ipRaw()));
        String portS = QrData.clean(I18n.get("blockprintlink.overlay.port", QrData.portRaw()));
        String tS     = QrData.clean(I18n.get("blockprintlink.overlay.token", QrData.tokenRaw()));
        String keyS   = QrData.clean(I18n.get("blockprintlink.overlay.hotkey_hint",
            BridgeConfig.hotkeyName()));

        g.drawString(font, ipS,   ix + 18, iy + 4,        QrData.C_TEXT, false);
        g.drawString(font, portS, ix + 18, iy + rh + 4,   QrData.C_TEXT, false);

        tokenX = ix + 18; tokenY = iy + rh * 2 + 4; tokenW = font.width(tS);
        hoverCopy = mx >= tokenX && mx <= tokenX + tokenW && my >= tokenY - 1 && my <= tokenY + 10;
        g.drawString(font, tS, tokenX, tokenY, hoverCopy ? 0xFF00BCD4 : QrData.C_TEXT, false);
        if (hoverCopy) g.drawString(font, " ⚡", tokenX + tokenW, tokenY, 0xFF00BCD4, false);

        g.drawString(font, keyS, bx + bw / 2 - font.width(keyS) / 2,
            iy + rh * 3 + 4, QrData.C_TEXT_DIM, false);

        int fy = by + bh - 14;
        g.hLine(bx, bx + bw, fy, 0xFF8B8B8B);
        g.fill(bx, fy + 1, bx + bw, by + bh, QrData.C_ACCENT);
        String ver = Component.translatable("blockprintlink.ui.version",
            io.github.moxisuki.blockprint.link.bridge.ClientMeta.mcVersion()).getString();
        g.drawString(font, ver, bx + bw - font.width(ver) - 6, fy + 4, 0xFF888888, false);
    }

    private static void slotBorder(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF373737);
        g.hLine(x, x + w - 1, y, 0xFFFFFFFF);
        g.vLine(x, y, y + h - 1, 0xFFFFFFFF);
        g.hLine(x + 1, x + w, y + h - 1, 0xFF8B8B8B);
        g.vLine(x + w - 1, y + 1, y + h, 0xFF8B8B8B);
    }
}
