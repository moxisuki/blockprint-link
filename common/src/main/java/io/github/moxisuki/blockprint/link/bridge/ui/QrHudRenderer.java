package io.github.moxisuki.blockprint.link.bridge.ui;

import io.github.moxisuki.blockprint.link.bridge.BridgeConfig;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * QR overlay — vanilla-style dark panel, centered QR, shadowed text.
 * Pixel array + display strings cached; renders via horizontal-line
 * batching for low frame-time cost.
 */
public final class QrHudRenderer {

    private static final AtomicBoolean visible = new AtomicBoolean(false);
    private static int[] qrPixels;
    private static int qrW, qrH;
    private static String lastToken;
    private static int lastTarget = -1;
    // Pre-cached display strings
    private static String cachedIpText, cachedPortText, cachedTokenText, cachedKeyText;

    private QrHudRenderer() {}

    public static void setVisible(boolean v) { visible.set(v); }
    public static boolean isVisible() { return visible.get(); }
    public static void toggle() { visible.set(!visible.get()); }

    public static void render(GuiGraphics graphics) {
        if (!visible.get() || qrPixels == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;

        int size = qrW, pad = 2, bgSize = size + pad * 2;
        int qrX = (mc.getWindow().getGuiScaledWidth() - size) / 2;
        int qrY = (mc.getWindow().getGuiScaledHeight() - size) / 2;

        // ── Vanilla dark panel ──
        int p = 8; // panel padding
        int by2 = qrY + size + p + 48;
        graphics.fill(qrX - p, qrY - p, qrX + size + p, by2, 0xCC000000);
        int border = 0xFF555555;
        graphics.hLine(qrX - p, qrX + size + p, qrY - p, border);
        graphics.hLine(qrX - p, qrX + size + p, by2, border);
        graphics.vLine(qrX - p, qrY - p, by2, border);
        graphics.vLine(qrX + size + p, qrY - p, by2, border);

        // ── QR: white bg + black horizontal runs ──
        graphics.fill(qrX - pad, qrY - pad, qrX + bgSize, qrY + bgSize, 0xFFFFFFFF);
        for (int y = 0; y < qrH; y++) {
            int runStart = -1;
            for (int x = 0; x <= qrW; x++) {
                boolean black = x < qrW && (qrPixels[y * qrW + x] & 0x00FFFFFF) == 0;
                if (black && runStart < 0) runStart = x;
                else if (!black && runStart >= 0) {
                    graphics.hLine(qrX + runStart, qrX + x - 1, qrY + y, 0xFF000000);
                    runStart = -1;
                }
            }
        }

        // ── Text below QR, shadowed, from cache ──
        int tc = qrX + size / 2;
        int ty = qrY + size + pad + 13;
        drawShadowed(graphics, mc, cachedIpText, tc, ty, 0xFFFFFFFF);
        drawShadowed(graphics, mc, cachedPortText, tc, ty + 11, 0xFFFFFFFF);
        drawShadowed(graphics, mc, cachedTokenText, tc, ty + 22, 0xFFFFFFFF);
        drawShadowed(graphics, mc, cachedKeyText, tc, ty + 33, 0xFFAAAAAA);
    }

    private static void drawShadowed(GuiGraphics g, Minecraft mc, String text, int cx, int y, int color) {
        if (text == null) return;
        int w = mc.font.width(text);
        int x = cx - w / 2;
        g.drawString(mc.font, text, x, y, color, true);
    }

    private static void buildQr(String token, int port, String ip, int target) {
        try {
            String uri = "blockprintcat://" + ip + ":" + port + "/ws?token=" + token;
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix matrix = new QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, target, target, hints);
            qrW = matrix.getWidth(); qrH = matrix.getHeight();
            int[] pixels = new int[qrW * qrH];
            for (int y = 0; y < qrH; y++)
                for (int x = 0; x < qrW; x++)
                    pixels[y * qrW + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            qrPixels = pixels;
            // Cache display strings
            String st = token.length() > 12 ? token.substring(0, 12) + ".." : token;
            cachedIpText   = I18n.get("blockprintlink.overlay.ip", ip);
            cachedPortText = I18n.get("blockprintlink.overlay.port", port);
            cachedTokenText = I18n.get("blockprintlink.overlay.token", st);
            cachedKeyText  = I18n.get("blockprintlink.overlay.hotkey_hint", BridgeConfig.hotkeyName());
            lastToken = token; lastTarget = target;
        } catch (Exception e) { qrPixels = null; }
    }
}
