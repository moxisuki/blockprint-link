package io.github.moxisuki.blockprint.link.bridge;

import io.github.moxisuki.blockprint.link.LitematicMod;
import io.github.moxisuki.blockprint.link.LogUtil;

import io.github.moxisuki.blockprint.link.schematic.SchematicScanner;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Lightweight RFC 6455 WebSocket server. Built on a raw {@link ServerSocket}
 * (not the JDK's {@code HttpServer}) because we need the 101 Switching
 * Protocols response with custom headers, which HttpServer can't do.
 *
 * <h2>Wire protocol v2 (upload)</h2>
 *
 * <p>The previous single-frame upload (one {@code upload} text frame + an
 * immediate binary body) raced with the browser's WebSocket fragmentation
 * for any file &gt; ~32 KiB. The new protocol is two-phase:
 *
 * <pre>{@code
 *   C → S  [text]  {"type":"upload/init", "fileName":"foo", "size":33711,
 *                    "sha256":"abc..."  (optional)}
 *   S → C  [text]  {"type":"upload/ready", "fileName":"foo"}
 *   C → S  [binary] chunk1 (any size)
 *   C → S  [binary] chunk2
 *   C → S  [text]  {"type":"upload/commit", "fileName":"foo"}
 *   S → C  [text]  {"type":"upload/result", "fileName":"foo", "ok":true,
 *                    "sha256":"<computed>"}  or  "ok":false, "error":"..."
 * }</pre>
 *
 * <p>Binary frames outside the {@code init → ready} window are silently
 * dropped (with a warn) so an over-eager client can't poison the
 * accumulator.
 */
public final class SchematicsWsServer {

    private static final String WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Pattern TOKEN_PATTERN = Pattern.compile(".+");

    private final SchematicsWatcher watcher;
    private final Map<String, ClientSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private Thread acceptorThread;

    public SchematicsWsServer(SchematicsWatcher watcher) {
        this.watcher = watcher;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        try {
            serverSocket = new ServerSocket(BridgeConfig.wsPort());
            acceptorThread = new Thread(this::acceptLoop, "blockprintlink-ws-accept");
            acceptorThread.setDaemon(true);
            acceptorThread.start();
            LogUtil.info("[BlockPrintLink/Bridge] WebSocket server listening on :" + BridgeConfig.wsPort());
            watcher.addListener(this::broadcastChange);
        } catch (IOException e) {
            LogUtil.error("[BlockPrintLink/Bridge] Failed to start WebSocket server: " + e.getMessage());
            running.set(false);
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        for (ClientSession s : sessions.values()) s.close();
        sessions.clear();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket s = serverSocket.accept();
                Thread t = new Thread(() -> handleConnection(s), "blockprintlink-ws-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running.get()) LogUtil.error("[BlockPrintLink/Bridge] Accept failed: " + e.getMessage());
            }
        }
    }

    private void handleConnection(Socket socket) {
        try {
            socket.setSoTimeout(0);
            InputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null) { socket.close(); return; }
            java.util.HashMap<String, String> headers = new java.util.HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !"GET".equalsIgnoreCase(parts[0])) {
                sendHttpError(out, 400, "Bad request");
                socket.close(); return;
            }
            String target = parts[1];
            if (!target.startsWith("/ws")) { sendHttpError(out, 404, "Not Found"); socket.close(); return; }
            String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";
            String token = extractToken(query);

            String upgrade = headers.get("Upgrade");
            String connection = headers.get("Connection");
            String key = headers.get("Sec-WebSocket-Key");
            String version = headers.get("Sec-WebSocket-Version");
            if (!"websocket".equalsIgnoreCase(upgrade)
                || connection == null || !connection.toLowerCase().contains("upgrade")
                || key == null || !"13".equals(version)) {
                sendHttpError(out, 400, "Bad WebSocket upgrade");
                socket.close(); return;
            }
            if (token == null || !token.equalsIgnoreCase(BridgeConfig.sessionToken())) {
                sendHttpError(out, 401, "AUTH_FAILED");
                socket.close(); return;
            }

            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update((key + WS_MAGIC).getBytes(StandardCharsets.US_ASCII));
            String accept = Base64.getEncoder().encodeToString(md.digest());
            String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            out.write(resp.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            ClientSession session = new ClientSession(socket, in, out);
            sessions.put(session.id, session);
            LogUtil.info("[BlockPrintLink/Bridge] Client connected (" + session.id + "); total=" + sessions.size());

            try {
                while (running.get() && session.alive.get()) {
                    Frame frame = readFrame(in);
                    if (frame == null) break;
                    handleFrame(session, frame);
                }
            } finally {
                session.close();
                sessions.remove(session.id);
                LogUtil.info("[BlockPrintLink/Bridge] Client disconnected (" + session.id + ")");
            }
        } catch (Exception e) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private void handleFrame(ClientSession session, Frame frame) {
        if (frame.opcode == 0x8) { session.alive.set(false); return; }
        if (frame.opcode == 0x9) { session.sendPong(frame.payload); return; }

        // Binary (0x2) and continuation (0x0) frames both feed the
        // upload accumulator. Continuation is only relevant if the
        // client fragments a binary frame, but accepting it costs us
        // nothing and avoids UNEXPECTED_BINARY if it happens.
        if (frame.opcode == 0x2 || frame.opcode == 0x0) {
            if (session.uploadState == UploadState.RECEIVING) {
                session.uploadAccumulator.write(frame.payload, 0, frame.payload.length);
                session.uploadReceived += frame.payload.length;
            } else {
                // Binary frame outside the init→ready→commit window.
                // Warn once, drop. Common cause: a previous upload never
                // sent commit (client crashed / disconnected mid-way).
                LogUtil.warn("[BlockPrintLink/Bridge] Orphan binary frame ("
                    + frame.payload.length + " bytes, opcode=0x"
                    + Integer.toHexString(frame.opcode) + ") — no active upload");
            }
            return;
        }

        if (frame.opcode == 0x1) {
            try {
                Map<String, String> msg = MiniJson.parseObject(new String(frame.payload, StandardCharsets.UTF_8));
                String type = msg.getOrDefault("type", "");
                String requestId = msg.get("requestId");
                switch (type) {
                    case "list" -> handleList(session, requestId);
                    case "download" -> handleDownload(session, requestId, msg);
                    case "upload/init" -> handleUploadInit(session, msg);
                    case "upload/commit" -> handleUploadCommit(session, msg);
                    default -> session.sendError(requestId, "UNKNOWN_TYPE", "Unknown message type: " + type);
                }
            } catch (Exception e) {
                session.sendError(null, "BAD_JSON", e.getMessage());
            }
        }
    }

    private String metaPrefix() {
        return "\"mcVersion\":" + MiniJson.quote(ClientMeta.mcVersion())
            + ",\"loader\":" + MiniJson.quote(ClientMeta.loader())
            + ",\"loaderVersion\":" + MiniJson.quote(ClientMeta.loaderVersion())
            + ",\"folderName\":" + MiniJson.quote(ClientMeta.folderName(watcher.getSchematicsDir()));
    }

    private void handleList(ClientSession session, String requestId) {
        List<SchematicScanner.Entry> entries = SchematicScanner.scanAll(watcher.getSchematicsDir().getParentFile());
        StringBuilder sb = new StringBuilder("{\"type\":\"list/response\"");
        if (requestId != null) sb.append(",\"requestId\":").append(MiniJson.quote(requestId));
        sb.append(",\"ok\":true,").append(metaPrefix()).append(",\"entries\":");
        sb.append(MiniJson.entriesToJsonArray(entries));
        sb.append('}');
        session.sendText(sb.toString());
    }

    private void broadcastChange(List<SchematicScanner.Entry> entries) {
        String text = "{\"type\":\"list/changed\"," + metaPrefix() + ",\"entries\":" + MiniJson.entriesToJsonArray(entries) + "}";
        for (ClientSession s : sessions.values()) {
            if (s.alive.get()) s.sendText(text);
        }
    }

    private void handleDownload(ClientSession session, String requestId, Map<String, String> msg) {
        // Download v2: client sends download/init, we resolve, send
        // download/ready with metadata, then send one or more binary
        // chunks, then download/done. Symmetric with upload v2 — the
        // ready/done text frames give the receiver explicit handoffs
        // instead of relying on the 0x82 opcode boundary.
        String fileName = msg.get("fileName");
        String source = msg.getOrDefault("source", "schematics");
        if (fileName == null || fileName.isEmpty()) {
            session.sendError(requestId, "BAD_REQUEST", "fileName required");
            return;
        }
        if (!isSafeFileName(fileName)) {
            session.sendError(requestId, "BAD_FILENAME", "Invalid file name");
            return;
        }
        File target = resolveDownloadFile(fileName, source);
        if (target == null || !target.isFile()) {
            session.sendError(requestId, "FILE_NOT_FOUND", "No such file: " + fileName + " (source=" + source + ")");
            return;
        }
        if (target.length() > BridgeConfig.MAX_FILE_SIZE_BYTES) {
            session.sendError(requestId, "FILE_TOO_LARGE",
                "File exceeds " + BridgeConfig.MAX_FILE_SIZE_BYTES + " bytes");
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(target.toPath());
            String sha = sha256Hex(bytes);
            // download/ready announces metadata + accepts any pending
            // download/cancel from the client.
            StringBuilder h = new StringBuilder("{\"type\":\"download/ready\"");
            if (requestId != null) h.append(",\"requestId\":").append(MiniJson.quote(requestId));
            h.append(",\"fileName\":").append(MiniJson.quote(fileName))
             .append(",\"size\":").append(bytes.length)
             .append(",\"sha256\":\"").append(sha).append("\"");
            if (!"schematics".equals(source)) h.append(",\"source\":").append(MiniJson.quote(source));
            h.append('}');
            session.sendText(h.toString());

            // Send in ≤ 65535-byte chunks. Staying at or below 0xFFFF
            // keeps each binary frame in the 16-bit extended-length
            // encoding range — 65536 (0x10000) would trigger the
            // 8-byte extended path, which has a known off-by issue
            // in the Python websockets client's frame parser.
            final int CHUNK = 65535;
            for (int off = 0; off < bytes.length; off += CHUNK) {
                int len = Math.min(CHUNK, bytes.length - off);
                session.sendBinary(len == bytes.length ? bytes : java.util.Arrays.copyOfRange(bytes, off, off + len));
            }

            // download/done: final framing + error code if anything
            // went wrong mid-stream (sockets broken, client cancelled).
            StringBuilder d = new StringBuilder("{\"type\":\"download/done\"");
            if (requestId != null && !requestId.isEmpty()) {
                d.append(",\"requestId\":").append(MiniJson.quote(requestId));
            }
            d.append(",\"fileName\":").append(MiniJson.quote(fileName))
             .append(",\"ok\":true")
             .append(",\"bytes\":").append(bytes.length)
             .append(",\"sha256\":\"").append(sha).append("\"")
             .append('}');
            session.sendText(d.toString());
        } catch (IOException e) {
            session.sendError(requestId, "IO_ERROR", e.getMessage());
        }
    }

    private File resolveDownloadFile(String fileName, String source) {
        File gameDir = watcher.getSchematicsDir().getParentFile();
        if (source == null || "schematics".equals(source)) {
            return new File(watcher.getSchematicsDir(), fileName);
        }
        if (source.startsWith("saves/")) {
            String worldName = source.substring("saves/".length());
            return new File(gameDir, "saves/" + worldName + "/generated/minecraft/structures/" + fileName);
        }
        if (SchematicScanner.SOURCE_WORLDEDIT.equals(source)) {
            return new File(gameDir,
                SchematicScanner.WORLDEDIT_SCHEMATICS_SUBDIR + "/" + fileName);
        }
        return null;
    }

    /**
     * Route an upload to the right directory based on file format.
     * Sponge (.schem / .schematic) → {@code <gameDir>/config/worldedit/schematics/}
     * when WorldEdit is loaded; everything else → {@code <gameDir>/schematics/}.
     */
    private File resolveUploadTarget(String fileName) {
        File gameDir = watcher.getSchematicsDir().getParentFile();
        String lower = fileName.toLowerCase(Locale.ROOT);
        boolean isSponge = lower.endsWith(".schem") || lower.endsWith(".schematic");
        if (isSponge && ModDetection.isWorldEditLoaded()) {
            File weDir = new File(gameDir, SchematicScanner.WORLDEDIT_SCHEMATICS_SUBDIR);
            if (weDir.isDirectory() || weDir.mkdirs()) {
                LogUtil.info("[BlockPrintLink/Bridge] Upload " + fileName + " -> WorldEdit schematics dir");
                return new File(weDir, fileName);
            }
            LogUtil.warn("[BlockPrintLink/Bridge] Cannot create WorldEdit schematics dir "
                + weDir.getAbsolutePath() + " — falling back to schematics/");
        }
        LogUtil.info("[BlockPrintLink/Bridge] Upload " + fileName + " -> schematics/");
        return new File(watcher.getSchematicsDir(), fileName);
    }

    // ── Upload protocol v2: init → ready → chunks → commit ─────────────
    //
    // Single active upload per connection. requestId is echoed in every
    // server response so the client can demultiplex when running tasks
    // back-to-back on the same socket — even though only one upload is
    // in flight at a time, the requestId lets the client's completion
    // callback resolve the right Promise/Future.

    private void handleUploadInit(ClientSession session, Map<String, String> msg) {
        String fileName = msg.get("fileName");
        String requestId = msg.getOrDefault("requestId", "");
        boolean overwrite = "true".equalsIgnoreCase(msg.get("overwrite"));
        long size = parseLongOrZero(msg.get("size"));
        String clientSha = msg.get("sha256");

        if (session.uploadState == UploadState.RECEIVING) {
            LogUtil.warn("[BlockPrintLink/Bridge] Upload init for " + fileName
                + " (requestId=" + requestId + ") but another upload is in progress: "
                + session.uploadFileName);
            session.sendUploadResult(requestId, fileName, false, "BUSY");
            return;
        }

        if (fileName == null || !isSafeFileName(fileName)) {
            LogUtil.warn("[BlockPrintLink/Bridge] Upload init rejected: bad filename " + fileName);
            session.sendUploadResult(requestId, fileName, false, "BAD_FILENAME");
            return;
        }
        if (size <= 0 || size > BridgeConfig.MAX_FILE_SIZE_BYTES) {
            LogUtil.warn("[BlockPrintLink/Bridge] Upload init rejected: " + fileName
                + " bad size " + size);
            session.sendUploadResult(requestId, fileName, false, "FILE_TOO_LARGE");
            return;
        }
        File target = resolveUploadTarget(fileName);
        if (target.exists() && !overwrite) {
            LogUtil.info("[BlockPrintLink/Bridge] Upload init rejected: " + fileName + " exists");
            session.sendUploadResult(requestId, fileName, false, "FILE_EXISTS");
            return;
        }

        session.uploadState = UploadState.RECEIVING;
        session.uploadRequestId = requestId;
        session.uploadFileName = fileName;
        session.uploadTarget = target;
        session.uploadExpectedSize = size;
        session.uploadClientSha = clientSha;
        session.uploadAccumulator = new ByteArrayOutputStream();
        session.uploadReceived = 0;

        LogUtil.info("[BlockPrintLink/Bridge] Upload init [" + requestId + "]: " + fileName
            + " (" + size + " bytes) -> " + target.getAbsolutePath());
        session.sendText("{\"type\":\"upload/ready\",\"requestId\":"
            + MiniJson.quote(requestId) + ",\"fileName\":" + MiniJson.quote(fileName) + "}");
    }

    private void handleUploadCommit(ClientSession session, Map<String, String> msg) {
        String fileName = msg.get("fileName");
        String requestId = msg.getOrDefault("requestId", "");
        if (session.uploadState != UploadState.RECEIVING
            || !fileName.equals(session.uploadFileName)
            || !requestId.equals(session.uploadRequestId)) {
            LogUtil.warn("[BlockPrintLink/Bridge] Upload commit for " + fileName
                + " (requestId=" + requestId + ") doesn't match active upload ("
                + session.uploadFileName + ", " + session.uploadRequestId + ")");
            session.sendUploadResult(requestId, fileName, false, "NO_ACTIVE_UPLOAD");
            resetUpload(session);
            return;
        }
        if (session.uploadReceived != session.uploadExpectedSize) {
            LogUtil.warn("[BlockPrintLink/Bridge] Upload length mismatch [" + requestId + "]: "
                + fileName + " expected=" + session.uploadExpectedSize + " got=" + session.uploadReceived);
            session.sendUploadResult(requestId, fileName, false, "LENGTH_MISMATCH");
            resetUpload(session);
            return;
        }

        try {
            byte[] full = session.uploadAccumulator.toByteArray();
            String actualSha = sha256Hex(full);
            LogUtil.info("[BlockPrintLink/Bridge] Upload commit [" + requestId + "]: " + fileName
                + " expectedSize=" + session.uploadExpectedSize
                + " actualSize=" + full.length
                + " clientSha=" + (session.uploadClientSha != null
                    ? session.uploadClientSha.substring(0, Math.min(8, session.uploadClientSha.length())) + "..." : "null")
                + " serverSha=" + actualSha.substring(0, Math.min(8, actualSha.length())) + "...");

            if (session.uploadClientSha != null
                && !session.uploadClientSha.equalsIgnoreCase(actualSha)) {
                LogUtil.warn("[BlockPrintLink/Bridge] Upload SHA mismatch [" + requestId + "]: " + fileName
                    + " client=" + session.uploadClientSha + " server=" + actualSha);
                session.sendUploadResult(requestId, fileName, false, "SHA_MISMATCH");
                resetUpload(session);
                return;
            }

            try (var fos = new java.io.FileOutputStream(session.uploadTarget)) {
                fos.write(full);
            }
            LogUtil.info("[BlockPrintLink/Bridge] Upload complete [" + requestId + "]: " + fileName
                + " (" + full.length + " bytes) -> " + session.uploadTarget.getAbsolutePath());
            session.sendUploadResult(requestId, fileName, true, null);
            session.sendText("{\"type\":\"upload/done\",\"requestId\":"
                + MiniJson.quote(requestId)
                + ",\"fileName\":" + MiniJson.quote(fileName)
                + ",\"sha256\":\"" + actualSha + "\"}");

            // Push a generic in-game chat notification so the player knows the
            // bridge just received a blueprint. The notification fires
            // for ANY uploaded format (.litematic / .schem / .nbt / .json
            // / etc.) whenever the user has chat messages enabled
            // (BridgeConfig.showChatMessages). The clickable "点此复制"
// suffix is ONLY added when BG2 is also loaded AND the file looks
// like a BG2 template (.json fitting the click cache) — that's a
// sub-condition, not the master switch.
// Best-effort — never fail the upload because chat failed.
            if (BridgeConfig.showChatMessages()) {
                try {
                    LitematicMod.broadcastComponentToCurrentPlayer(
                        buildUploadChatMessage(fileName, full));
                } catch (Throwable t) {
                    LogUtil.warn("[BlockPrintLink/Bridge] upload chat broadcast failed: " + t);
                }
            }
        } catch (IOException e) {
            LogUtil.error("[BlockPrintLink/Bridge] Upload IO error [" + requestId + "]: " + fileName
                + " -> " + session.uploadTarget.getAbsolutePath(), e);
            session.sendUploadResult(requestId, fileName, false, "IO_ERROR: " + e.getMessage());
        } finally {
            resetUpload(session);
        }
    }

    private static void resetUpload(ClientSession session) {
        session.uploadState = UploadState.IDLE;
        session.uploadRequestId = null;
        session.uploadFileName = null;
        session.uploadTarget = null;
        session.uploadExpectedSize = 0;
        session.uploadClientSha = null;
        session.uploadAccumulator = null;
        session.uploadReceived = 0;
    }

    /**
     * Build the upload chat notification. For ANY uploaded file the
     * message reads "Blueprint received: &lt;fileName&gt;" (so the player
     * knows the bridge landed the upload). When the file is a BG2
     * template (.json) small enough to fit in the click-cache, a
     * clickable green "点此复制" / "Click to copy" suffix is appended,
     * wired to {@code /blockprintlink copy-bg2 <id>} so the player can
     * one-click copy the full content to the clipboard.
     *
     * <p>Click payload is small (UUID only); bytes ride in
     * {@link Bg2ClipboardCache} on the client side.
     */
    private static Component buildUploadChatMessage(String fileName, byte[] full) {
        String langKey = langKeyForFile(fileName);
        // Component.translatable() returns MutableComponent, so .append()
        // chains work — just don't downgrade the type to Component.
        var msg = Component.translatable(langKey, fileName);
        if (!isClickableJson(fileName, full)) {
            return msg;
        }
        String id = UUID.randomUUID().toString();
        Bg2ClipboardCache.put(id, full);
        var clickPart = Component.literal("§a§n[" + copyLabel() + "]")
            .withStyle(style -> {
                // Prefer typed PlatformHooks (ForgeGradle remaps the field refs,
                // no reflection needed). Fall back to reflection-based builders
                // for loaders that haven't set PlatformHooks yet.
                ClickEvent ce = PlatformHooks.hasImpl()
                    ? PlatformHooks.makeClickEvent("blockprintlink copy-bg2 " + id)
                    : makeClickEvent("blockprintlink copy-bg2 " + id);
                if (ce != null) style = style.withClickEvent(ce);
                HoverEvent he = PlatformHooks.hasImpl()
                    ? PlatformHooks.makeHoverEvent(Component.translatable("blockprintlink.chat.copy_tooltip"))
                    : makeHoverEvent(Component.translatable("blockprintlink.chat.copy_tooltip"));
                if (he != null) style = style.withHoverEvent(he);
                return style;
            });
        // → "[BlockPrint] 建筑小帮手蓝图已接收: d0194...json — [点此复制]"
        return msg
            .append(Component.literal(" §7— "))
            .append(clickPart);
    }

    /** Pick the per-format lang key based on file extension. */
    private static String langKeyForFile(String fileName) {
        if (fileName == null) return "blockprintlink.chat.bp_json_received";
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".litematic")) return "blockprintlink.chat.bp_litematica_received";
        if (lower.endsWith(".schem") || lower.endsWith(".schematic")) return "blockprintlink.chat.bp_sponge_received";
        if (lower.endsWith(".nbt")) return "blockprintlink.chat.bp_structure_received";
        if (lower.endsWith(".json")) return "blockprintlink.chat.bp_json_received";
        return "blockprintlink.chat.bp_json_received";
    }

    /** Whether a .json upload qualifies for the click-to-copy suffix. */
    private static boolean isClickableJson(String fileName, byte[] full) {
        if (fileName == null || full == null) return false;
        if (full.length == 0 || full.length > Bg2ClipboardCache.MAX_BYTES_PER_ENTRY) return false;
        if (!ModDetection.isBuildingGadgets2Loaded()) return false;
        return fileName.toLowerCase(Locale.ROOT).endsWith(".json");
    }

    // ── Cross-version ClickEvent / HoverEvent builders ──────────────────
    //
    // MC 1.21.5+ made these classes abstract with static factory methods
    // (ClickEvent.runCommand(…) / HoverEvent.showText(…)).
    // 1.21.1-1.21.4 use the old new ClickEvent(Action, String) /
    // new HoverEvent(Action, Component) constructors.
    // Reflection bridges both so common compiles against any version.

    private static ClickEvent makeClickEvent(String command) {
        try {
            // 1.21.5+: ClickEvent.runCommand(String)
            java.lang.reflect.Method m = ClickEvent.class.getMethod("runCommand", String.class);
            return (ClickEvent) m.invoke(null, command);
        } catch (NoSuchMethodException e1) {
            try {
                // 1.21.1-1.21.4: ClickEvent(Action, String)
                // Note: Action is an enum on 1.21.x but a class with static fields
                // on 1.20.1. getField works for both.
                Class<?> actionClass = Class.forName("net.minecraft.network.chat.ClickEvent$Action");
                Object action = actionClass.getField("RUN_COMMAND").get(null);
                return ClickEvent.class.getConstructor(actionClass, String.class)
                    .newInstance(action, command);
            } catch (Exception e2) {
                LogUtil.warn("[BlockPrintLink/Bridge] Cannot create ClickEvent: " + e2);
                return null;
            }
        } catch (Exception e) {
            LogUtil.warn("[BlockPrintLink/Bridge] Cannot create ClickEvent: " + e);
            return null;
        }
    }

    private static HoverEvent makeHoverEvent(Component text) {
        try {
            // 1.21.5+: HoverEvent.showText(Component)
            java.lang.reflect.Method m = HoverEvent.class.getMethod("showText", Component.class);
            return (HoverEvent) m.invoke(null, text);
        } catch (NoSuchMethodException e1) {
            try {
                // 1.21.1-1.21.4: HoverEvent(Action, Component)
                // Action is an enum on 1.21.x, class with static fields on 1.20.1.
                Class<?> actionClass = Class.forName("net.minecraft.network.chat.HoverEvent$Action");
                Object action = actionClass.getField("SHOW_TEXT").get(null);
                return HoverEvent.class.getConstructor(actionClass, Component.class)
                    .newInstance(action, text);
            } catch (Exception e2) {
                LogUtil.warn("[BlockPrintLink/Bridge] Cannot create HoverEvent: " + e2);
                return null;
            }
        } catch (Exception e) {
            LogUtil.warn("[BlockPrintLink/Bridge] Cannot create HoverEvent: " + e);
            return null;
        }
    }

    /** Locale-aware clickable label; mirrors the existing lang files. */
    private static String copyLabel() {
        // Avoid importing net.minecraft.client.Minecraft — it's not on
        // the classpath for standalone Fabric builds. Java system locale
        // is a reasonable proxy on single-player machines.
        if (java.util.Locale.getDefault().getLanguage().startsWith("zh")) return "点此复制";
        return "Click to copy";
    }

    private enum UploadState { IDLE, RECEIVING }

    private Frame readFrame(InputStream in) throws IOException {
        // Wrapped by DataInputStream upstream so read(byte[], int, int)
        // implements the "read until len or EOF" contract.
        int b1 = in.read();
        int b2 = in.read();
        if (b1 < 0 || b2 < 0) return null;
        int opcode = b1 & 0x0F;
        boolean masked = (b2 & 0x80) != 0;
        long len = b2 & 0x7F;
        if (len == 126) {
            // in.read() returns signed int for bytes ≥ 0x80. MUST mask with
            // 0xFF before shifting — otherwise sign extension corrupts the
            // length (0x83AF → 33495 instead of 33711).
            int h = in.read() & 0xFF;
            int l = in.read() & 0xFF;
            len = ((h << 8) | l) & 0xFFFFL;
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF);
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();
        }
        byte[] payload = new byte[(int) len];
        if (len > 0) {
            int totalRead = 0;
            while (totalRead < len) {
                int n = in.read(payload, totalRead, (int) len - totalRead);
                if (n < 0) throw new IOException("Unexpected EOF reading frame");
                totalRead += n;
            }
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                }
            }
        }
        return new Frame(opcode, payload);
    }

    private record Frame(int opcode, byte[] payload) {}

    private static final class ClientSession {
        private static final AtomicLong COUNTER = new AtomicLong();
        final String id = "c" + COUNTER.incrementAndGet();
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        final java.util.concurrent.atomic.AtomicBoolean alive = new java.util.concurrent.atomic.AtomicBoolean(true);

        // Upload v2 state — single in-flight upload per connection.
        UploadState uploadState = UploadState.IDLE;
        String uploadRequestId;
        String uploadFileName;
        File uploadTarget;
        long uploadExpectedSize;
        String uploadClientSha;
        ByteArrayOutputStream uploadAccumulator;
        long uploadReceived;

        ClientSession(Socket socket, InputStream in, OutputStream out) {
            this.socket = socket;
            this.in = in;
            this.out = out;
        }

        synchronized void sendText(String text) {
            if (!alive.get()) return;
            try {
                byte[] data = text.getBytes(StandardCharsets.UTF_8);
                out.write(0x81);
                writeLength(data.length, out);
                out.write(data);
                out.flush();
            } catch (IOException e) { alive.set(false); }
        }

        synchronized void sendBinary(byte[] data) {
            if (!alive.get()) return;
            try {
                out.write(0x82);
                writeLength(data.length, out);
                out.write(data);
                out.flush();
            } catch (IOException e) { alive.set(false); }
        }

        synchronized void sendPong(byte[] payload) {
            if (!alive.get()) return;
            try {
                out.write(0x8A);
                writeLength(payload.length, out);
                out.write(payload);
                out.flush();
            } catch (IOException ignored) {}
        }

        void sendError(String requestId, String code, String message) {
            StringBuilder sb = new StringBuilder("{\"type\":\"error\"");
            if (requestId != null) sb.append(",\"requestId\":").append(MiniJson.quote(requestId));
            sb.append(",\"code\":").append(MiniJson.quote(code))
              .append(",\"message\":").append(MiniJson.quote(message))
              .append('}');
            sendText(sb.toString());
        }

        void sendUploadResult(String requestId, String fileName, boolean ok, String errorCode) {
            StringBuilder r = new StringBuilder("{\"type\":\"upload/result\"");
            if (requestId != null && !requestId.isEmpty()) {
                r.append(",\"requestId\":").append(MiniJson.quote(requestId));
            }
            r.append(",\"fileName\":").append(fileName == null ? "null" : MiniJson.quote(fileName));
            r.append(",\"ok\":").append(ok);
            if (errorCode != null) r.append(",\"error\":").append(MiniJson.quote(errorCode));
            r.append('}');
            sendText(r.toString());
        }

        void close() {
            alive.set(false);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static void writeLength(int len, OutputStream out) throws IOException {
        if (len < 126) {
            out.write(len);
        } else if (len < 65536) {
            out.write(126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            // 8-byte extended length (big-endian). Used only for >64 KiB
            // payloads — download chunks are 64 KiB max, so this branch
            // shouldn't trigger in practice, but keep it correct.
            out.write(127);
            for (int i = 7; i >= 0; i--) out.write((len >> (i * 8)) & 0xFF);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] data = baos.toByteArray();
                int end = data.length;
                if (end > 0 && data[end - 1] == '\r') end--;
                return new String(data, 0, end, StandardCharsets.US_ASCII);
            }
            baos.write(b);
        }
        return null;
    }

    private static String extractToken(String query) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if ("token".equals(pair.substring(0, eq))) {
                String v = pair.substring(eq + 1);
                if (TOKEN_PATTERN.matcher(v).matches()) return v;
            }
        }
        return null;
    }

    private static boolean isSafeFileName(String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")
            || name.contains("..") || name.startsWith(".")) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.endsWith(".litematic") || lower.endsWith(".schematic")
            || lower.endsWith(".schem") || lower.endsWith(".nbt")
            || lower.endsWith(".json");
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception e) {
            return "";
        }
    }

    private static long parseLongOrZero(String s) {
        if (s == null) return 0;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static void sendHttpError(OutputStream out, int code, String reason) {
        try {
            String body = "{\"code\":" + code + ",\"reason\":\"" + reason + "\"}";
            String resp = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "Connection: close\r\n\r\n" + body;
            out.write(resp.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException ignored) {}
    }
}
