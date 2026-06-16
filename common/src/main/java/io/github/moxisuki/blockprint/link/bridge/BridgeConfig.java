package io.github.moxisuki.blockprint.link.bridge;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.SecureRandom;

import io.github.moxisuki.blockprint.link.LogUtil;

/**
 * Runtime value store for the bridge. The platform config layer
 * (TOML) is the primary source for ports and flags. The session
 * token is persisted to {@code blockprintlink-token.txt} in the
 * game directory so it survives restarts.
 */
public final class BridgeConfig {

    public static final int DEFAULT_WS_PORT = 18080;
    public static final int DEFAULT_DISCOVERY_PORT = 18081;
    public static final long MAX_FILE_SIZE_BYTES = 100L * 1024 * 1024;
    public static final int DISCOVERY_INTERVAL_MS = 2000;

    /**
     * Default GLFW key code for the QR overlay toggle (F7 = 296).
     * Configurable at runtime via {@code -Dbridge_hotkey=<code>} on the JVM
     * command line, or the {@code BRIDGE_HOTKEY} environment variable.
     * GLFW F-row codes: F1=290 F2=291 F3=292 F4=293 F5=294 F6=295 F7=296
     *                   F8=297 F9=298 F10=299 F11=300 F12=301
     */
    public static final int DEFAULT_HOTKEY_CODE = 296;

    private static volatile String TOKEN = "";
    private static volatile boolean showChat = true;
    private static volatile int wsPort = DEFAULT_WS_PORT;
    private static volatile int discoveryPort = DEFAULT_DISCOVERY_PORT;
    private static volatile boolean enabled = true;
    private static File tokenFile;

    private static String freshToken() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(16);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private BridgeConfig() {}

    /**
     * Load or create the persisted token. Called once from
     * {@link LitematicBridge#start()} after the game directory
     * is known. The TOML config's token field still takes priority
     * (via {@link #applyValues}).
     */
    public static synchronized void initToken(File gameDir) {
        tokenFile = new File(gameDir, "blockprintlink-token.txt");
        if (tokenFile.isFile()) {
            try {
                String stored = Files.readString(tokenFile.toPath()).trim();
                if (!stored.isEmpty()) {
                    TOKEN = stored;
                    return;
                }
            } catch (IOException ignored) {}
        }
        TOKEN = freshToken();
        try {
            Files.writeString(tokenFile.toPath(), TOKEN);
        } catch (IOException ignored) {}
    }

    public static String sessionToken() { return TOKEN; }
    public static String tokenHint() {
        String t = TOKEN;
        return t.length() >= 4 ? t.substring(0, 4) : t;
    }

    /** Called by the platform config layer on init/reload. */
    public static void applyValues(String token, boolean chat, int ws, int dp) {
        if (token != null && !token.isBlank()) {
            TOKEN = token;
            // Persist the config override so it survives restarts too
            if (tokenFile != null && tokenFile.getParentFile().isDirectory()) {
                try { Files.writeString(tokenFile.toPath(), token); }
                catch (IOException ignored) {}
            }
        }
        showChat = chat;
        wsPort = ws > 0 ? ws : DEFAULT_WS_PORT;
        discoveryPort = dp > 0 ? dp : DEFAULT_DISCOVERY_PORT;
    }

    public static String freshTokenForSpec() { return freshToken(); }

    public static boolean enabled() { return enabled; }
    public static void setEnabled(boolean v) { enabled = v; }

    public static int wsPort() { return wsPort; }
    public static int discoveryPort() { return discoveryPort; }

    public static boolean showChatMessages() { return showChat; }

    /**
     * Read config from {@code blockprintlink-bridge.toml} in the game
     * directory. Fallback for platforms without a native config system
     * (Fabric). NeoForge/Forge use their own BridgeConfigSpec instead.
     */
    public static void loadConfigFile(File gameDir) {
        File f = new File(gameDir, "config/blockprintlink-bridge.toml");
        if (!f.isFile()) return;
        try {
            var lines = java.nio.file.Files.readAllLines(f.toPath());
            String section = "";
            String token = null;
            boolean chat = true;
            int ws = 0, dp = 0;
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.substring(1, line.length() - 1).trim();
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim().replace("\"", "");
                if (!"bridge".equals(section)) continue;
                switch (k) {
                    case "token" -> token = v;
                    case "showChatMessages" -> chat = Boolean.parseBoolean(v);
                    case "wsPort" -> ws = Integer.parseInt(v);
                    case "discoveryPort" -> dp = Integer.parseInt(v);
                }
            }
            applyValues(token, chat, ws, dp);
        } catch (Exception ignored) {}
    }

    /**
     * Resolve the QR overlay hotkey at runtime. Order of precedence:
     *   1. {@code -Dbridge_hotkey=<code>} system property
     *   2. {@code BRIDGE_HOTKEY} environment variable
     *   3. {@link #DEFAULT_HOTKEY_CODE} (F7)
     */
    public static int hotkeyCode() {
        String v = System.getProperty("bridge_hotkey", System.getenv("BRIDGE_HOTKEY"));
        if (v == null) return DEFAULT_HOTKEY_CODE;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_HOTKEY_CODE;
        }
    }

    // ── Dynamic key name ──────────────────────────────────────────
    // When set, hotkeyName() reads from this supplier (typically the
    // platform's KeyMapping.getTranslatedKeyMessage()) instead of
    // the static GLFW code → "F7" mapping. This keeps the QR overlay
    // in sync with what the user actually bound in Settings → Controls.

    private static java.util.function.Supplier<String> hotkeyNameSupplier = null;

    /** Called by platform code after the KeyMapping is registered. */
    public static void setHotkeyNameSupplier(java.util.function.Supplier<String> supplier) {
        hotkeyNameSupplier = supplier;
    }

    public static String hotkeyName() {
        if (hotkeyNameSupplier != null) {
            try { return hotkeyNameSupplier.get(); } catch (Exception ignored) {}
        }
        return hotkeyName(hotkeyCode());
    }

    private static String hotkeyName(int code) {
        if (code >= 290 && code <= 301) {
            // F1..F12
            return "F" + (code - 290 + 1);
        }
        return "Key" + code;
    }
}
