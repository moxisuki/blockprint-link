package io.github.moxisuki.blockprint.link.bridge.ui;

import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import io.github.moxisuki.blockprint.link.bridge.BridgeConfig;
import java.util.*;

/**
 * Shared QR-code data + layout constants. Platform renderers
 * (Screen, HUD overlay) call {@link #get()} to obtain the pixel
 * array and the pre-formatted display strings.
 */
public final class QrData {
    private static int[] qrPixels;
    private static int qrW, qrH;
    private static String lastToken;
    private static String connectUrl;

    // Cached display strings
    private static String ipText, portText, tokenText, keyText;
    private static String ipRaw, portRaw, tokenRaw;

    public static final int QR_SIZE = 88;
    public static final int PANEL_W = 200;
    public static final int PANEL_H = 210;
    public static final int ROW_H  = 20;

    // Vanilla container palette
    public static final int C_BG        = 0xC0101010;
    public static final int C_BORDER_D  = 0xFF2B2B2B;
    public static final int C_BORDER_L  = 0xFFFFFFFF;
    public static final int C_PANEL     = 0xFFC6C6C6;
    public static final int C_ACCENT    = 0xFF3D3D3D;
    public static final int C_TEXT      = 0xFF404040;
    public static final int C_TEXT_DIM  = 0xFF555555;

    private QrData() {}

    /** Ensure QR is built for current token. Returns true if ready. */
    public static boolean ensure() {
        String token = BridgeConfig.sessionToken();
        if (qrPixels != null && token.equals(lastToken)) {
            // Rebuild i18n strings every access — user may have switched language
            rebuildTexts(token, ipRaw, portRaw);
            return true;
        }
        return buildQr(token);
    }

    // ── accessors ───────────────────────────────────────────────

    public static int[] pixels() { return qrPixels; }
    public static int qrW() { return qrW; }
    public static int qrH() { return qrH; }
    public static String connectUrl() { return connectUrl; }

    public static String ipText()   { return ipText; }
    public static String portText() { return portText; }
    public static String tokenText(){ return tokenText; }
    public static String keyText()  { return keyText; }
    public static String ipRaw()    { return ipRaw; }
    public static String portRaw()  { return portRaw; }
    public static String tokenRaw() { return tokenRaw; }

    /** Strip Minecraft § color codes from an i18n string. */
    public static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        boolean skip = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§') { skip = true; continue; }
            if (skip) { skip = false; continue; }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Supply the key name for display (set by platform). */
    public static String keyName(String fallback) { return fallback; }

    // ── internals ───────────────────────────────────────────────

    private static void rebuildTexts(String token, String ip, String port) {
        ipText   = clean(getI18n("blockprintlink.overlay.ip", ip));
        portText = clean(getI18n("blockprintlink.overlay.port", port));
        tokenText = clean(getI18n("blockprintlink.overlay.token", token));
        keyText  = clean(getI18n("blockprintlink.overlay.hotkey_hint",
            BridgeConfig.hotkeyName()));
    }

    /** Avoids a compile-time dep on net.minecraft.client.resources.language.I18n */
    private static String getI18n(String key, Object... args) {
        try {
            return (String) Class.forName("net.minecraft.client.resources.language.I18n")
                .getMethod("get", String.class, Object[].class)
                .invoke(null, key, args);
        } catch (Exception e) { return key; }
    }

    private static boolean buildQr(String token) {
        try {
            String ip = detectLocalIp();
            int port = BridgeConfig.wsPort();
            connectUrl = "blockprintcat://" + ip + ":" + port + "/ws?token=" + token;
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
            hints.put(EncodeHintType.MARGIN, 0);
            BitMatrix matrix = new QRCodeWriter().encode(connectUrl, BarcodeFormat.QR_CODE,
                QR_SIZE, QR_SIZE, hints);
            qrW = matrix.getWidth(); qrH = matrix.getHeight();
            int[] px = new int[qrW * qrH];
            for (int y = 0; y < qrH; y++)
                for (int x = 0; x < qrW; x++)
                    px[y * qrW + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            qrPixels = px;
            lastToken = token;
            ipRaw = ip; portRaw = String.valueOf(port); tokenRaw = token;
            rebuildTexts(token, ip, String.valueOf(port));
            return true;
        } catch (Exception e) { qrPixels = null; return false; }
    }

    static String detectLocalIp() {
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
                    String h = a.getHostAddress();
                    if (h != null && !h.contains(":")) return h;
                }
            }
            return "127.0.0.1";
        } catch (Exception e) { return "127.0.0.1"; }
    }

}
