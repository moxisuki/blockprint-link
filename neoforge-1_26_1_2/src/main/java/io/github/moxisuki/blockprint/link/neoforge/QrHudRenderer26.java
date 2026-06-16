package io.github.moxisuki.blockprint.link.neoforge;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import io.github.moxisuki.blockprint.link.bridge.BridgeConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.language.I18n;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QrHudRenderer26 {
    private static int[] qrPixels;
    private static int qrW, qrH;
    private static final AtomicBoolean VISIBLE = new AtomicBoolean(false);
    private static String cachedIpText, cachedPortText, cachedTokenText, cachedKeyText;

    private static final int COLOR_BG     = 0xC0101010;
    private static final int COLOR_PANEL  = 0xFFC6C6C6;
    private static final int COLOR_BORDER_DARK  = 0xFF373737;
    private static final int COLOR_BORDER_LIGHT = 0xFFFFFFFF;
    private static final int COLOR_TEXT   = 0xFF404040;
    private static final int COLOR_TEXT_DIM = 0xFF707070;
    private static final int PANEL_W = 176;
    private static final int PANEL_H = 166;
    private static final int QR_SIZE = 72;

    private QrHudRenderer26() {}

    static {
        var p = System.getProperty("blockprintlink.bridge.qrSize",
            System.getenv().getOrDefault("BRIDGE_QR_SIZE", "72"));
        try {
            Integer.parseInt(p);
        } catch (NumberFormatException ignored) {}
    }

    public static void toggle() { VISIBLE.set(!VISIBLE.get()); }

    public static void renderLayer(GuiGraphicsExtractor g, net.minecraft.client.DeltaTracker dt) {
        render(g);
    }

    private static void render(GuiGraphicsExtractor g) {
        if (!VISIBLE.get()) return;
        if (qrPixels == null) {
            buildQr(BridgeConfig.sessionToken(), BridgeConfig.wsPort(),
                detectLocalIp(), QR_SIZE);
            if (qrPixels == null) return;
        }
        Minecraft mc = Minecraft.getInstance();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int px = (sw - PANEL_W) / 2;
        int py = (sh - PANEL_H) / 2;

        // Full-screen dim
        g.fill(0, 0, sw, sh, COLOR_BG);

        // Outer dark border
        g.fill(px, py, px + PANEL_W, py + PANEL_H, COLOR_BORDER_DARK);
        // Inner light border
        g.fill(px + 4, py + 4, px + PANEL_W - 4, py + PANEL_H - 4, COLOR_BORDER_LIGHT);
        // Panel body
        int bx = px + 5, by = py + 5;
        int bw = PANEL_W - 10, bh = PANEL_H - 10;
        g.fill(bx, by, bx + bw, by + bh, COLOR_PANEL);

        // Title
        g.text(mc.font, "BlockPrint Link", bx + 8, by + 6, COLOR_TEXT, true);

        // QR code
        int qrX = px + (PANEL_W - QR_SIZE) / 2;
        int qrY = by + 20;
        g.fill(qrX - 2, qrY - 2, qrX + QR_SIZE + 2, qrY + QR_SIZE + 2, 0xFF808080);
        g.fill(qrX, qrY, qrX + QR_SIZE, qrY + QR_SIZE, 0xFFFFFFFF);
        for (int y = 0; y < qrH; y++) {
            int runStart = -1;
            for (int x = 0; x <= qrW; x++) {
                boolean black = x < qrW && (qrPixels[y * qrW + x] & 0x00FFFFFF) == 0;
                if (black && runStart < 0) runStart = x;
                else if (!black && runStart >= 0) {
                    g.horizontalLine(qrX + runStart, qrX + x - 1, qrY + y, 0xFF000000);
                    runStart = -1;
                }
            }
        }

        // Info text
        int ty = qrY + QR_SIZE + 8;
        g.text(mc.font, cachedIpText,   bx + 8, ty,      COLOR_TEXT, false);
        g.text(mc.font, cachedPortText, bx + 8, ty + 10, COLOR_TEXT, false);
        g.text(mc.font, cachedTokenText,bx + 8, ty + 20, COLOR_TEXT, false);
        g.text(mc.font, cachedKeyText,  bx + 8, ty + 30, COLOR_TEXT_DIM, false);
    }

    public static void buildQr(String token, int port, String ip, int target) {
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
            String st = token.length() > 12 ? token.substring(0, 12) + ".." : token;
            cachedIpText   = I18n.get("blockprintlink.overlay.ip", ip);
            cachedPortText = I18n.get("blockprintlink.overlay.port", port);
            cachedTokenText = I18n.get("blockprintlink.overlay.token", st);
            cachedKeyText  = I18n.get("blockprintlink.overlay.hotkey_hint", BridgeConfig.hotkeyName());
        } catch (Exception ignored) { qrPixels = null; }
    }

    private static String detectLocalIp() {
        try {
            var ifaces = java.net.NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return "127.0.0.1";
            while (ifaces.hasMoreElements()) {
                var ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                var addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    var a = addrs.nextElement();
                    if (a.isLoopbackAddress()) continue;
                    String host = a.getHostAddress();
                    if (host == null || host.contains(":")) continue;
                    return host;
                }
            }
            return "127.0.0.1";
        } catch (Exception e) { return "127.0.0.1"; }
    }
}
