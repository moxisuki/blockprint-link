package io.github.moxisuki.blockprint.link.bridge;

import io.github.moxisuki.blockprint.link.LogUtil;

import io.github.moxisuki.blockprint.link.schematic.SchematicScanner;

import java.io.ByteArrayOutputStream;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Lightweight RFC 6455 WebSocket server. Built on a raw {@link ServerSocket}
 * (not the JDK's {@code HttpServer}) because we need the 101 Switching
 * Protocols response with custom headers, which HttpServer can't do.
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
            InputStream in = socket.getInputStream();
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
        if (frame.opcode == 0x1) {
            try {
                Map<String, String> msg = MiniJson.parseObject(new String(frame.payload, StandardCharsets.UTF_8));
                String type = msg.getOrDefault("type", "");
                String requestId = msg.get("requestId");
                switch (type) {
                    case "list" -> handleList(session, requestId);
                    case "download" -> handleDownload(session, requestId, msg);
                    case "upload" -> handleUploadStart(session, msg);
                    default -> session.sendError(requestId, "UNKNOWN_TYPE", "Unknown message type: " + type);
                }
            } catch (Exception e) {
                session.sendError(null, "BAD_JSON", e.getMessage());
            }
            return;
        }
        if (frame.opcode == 0x2) {
            if (session.pendingUpload != null) {
                handleUploadBody(session, frame.payload);
            } else {
                session.sendError(null, "UNEXPECTED_BINARY", "Binary frame without pending upload");
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
            StringBuilder h = new StringBuilder("{\"type\":\"download/start\"");
            if (requestId != null) h.append(",\"requestId\":").append(MiniJson.quote(requestId));
            h.append(",\"fileName\":").append(MiniJson.quote(fileName))
             .append(",\"size\":").append(bytes.length)
             .append(",\"sha256\":\"").append(sha).append("\"");
            if (!"schematics".equals(source)) h.append(",\"source\":").append(MiniJson.quote(source));
            h.append('}');
            session.sendText(h.toString());
            session.sendBinary(bytes);
        } catch (IOException e) {
            session.sendError(requestId, "IO_ERROR", e.getMessage());
        }
    }

    /**
     * Resolve a blueprint file by source.
     * "schematics" → {@code <gameDir>/schematics/<fileName>}
     * "saves/&lt;world&gt;" → {@code <gameDir>/saves/<world>/generated/minecraft/structures/<fileName>}
     */
    private File resolveDownloadFile(String fileName, String source) {
        File gameDir = watcher.getSchematicsDir().getParentFile();
        if (source == null || "schematics".equals(source)) {
            return new File(watcher.getSchematicsDir(), fileName);
        }
        if (source.startsWith("saves/")) {
            String worldName = source.substring("saves/".length());
            return new File(gameDir, "saves/" + worldName + "/generated/minecraft/structures/" + fileName);
        }
        return null;
    }

    private void handleUploadStart(ClientSession session, Map<String, String> msg) {
        String fileName = msg.get("fileName");
        long size = parseLongOrZero(msg.get("size"));
        boolean overwrite = "true".equalsIgnoreCase(msg.get("overwrite"));
        if (fileName == null || !isSafeFileName(fileName)) {
            session.sendUploadResult(fileName, false, "BAD_FILENAME");
            return;
        }
        if (size > BridgeConfig.MAX_FILE_SIZE_BYTES) {
            session.sendUploadResult(fileName, false, "FILE_TOO_LARGE");
            return;
        }
        File target = new File(watcher.getSchematicsDir(), fileName);
        if (target.exists() && !overwrite) {
            session.sendUploadResult(fileName, false, "FILE_EXISTS");
            return;
        }
        session.pendingUpload = new PendingUpload(fileName, size, msg.get("sha256"), target);
    }

    private void handleUploadBody(ClientSession session, byte[] payload) {
        PendingUpload up = session.pendingUpload;
        if (up == null) return;
        try {
            try (var fos = new java.io.FileOutputStream(up.target)) {
                fos.write(payload);
            }
            String actualSha = sha256Hex(Files.readAllBytes(up.target.toPath()));
            if (up.expectedSha != null && !up.expectedSha.equalsIgnoreCase(actualSha)) {
                Files.deleteIfExists(up.target.toPath());
                session.sendUploadResult(up.fileName, false, "SHA_MISMATCH");
            } else {
                session.sendUploadResult(up.fileName, true, null);
            }
        } catch (IOException e) {
            session.sendUploadResult(up.fileName, false, "IO_ERROR: " + e.getMessage());
        } finally {
            session.pendingUpload = null;
        }
    }

    private Frame readFrame(InputStream in) throws IOException {
        int b1 = in.read();
        int b2 = in.read();
        if (b1 < 0 || b2 < 0) return null;
        int opcode = b1 & 0x0F;
        boolean masked = (b2 & 0x80) != 0;
        long len = b2 & 0x7F;
        if (len == 126) {
            len = ((in.read() << 8) | in.read()) & 0xFFFFL;
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xFF);
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long remaining = len;
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int n = in.read(buf, 0, toRead);
            if (n < 0) throw new IOException("Unexpected EOF reading frame");
            baos.write(buf, 0, n);
            remaining -= n;
        }
        byte[] payload = baos.toByteArray();
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i % 4]);
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
        PendingUpload pendingUpload;

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

        void sendUploadResult(String fileName, boolean ok, String errorCode) {
            StringBuilder r = new StringBuilder("{\"type\":\"upload/result\"");
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

    private static final class PendingUpload {
        final String fileName;
        final long expectedSize;
        final String expectedSha;
        final File target;
        PendingUpload(String fileName, long expectedSize, String expectedSha, File target) {
            this.fileName = fileName;
            this.expectedSize = expectedSize;
            this.expectedSha = expectedSha;
            this.target = target;
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
            || lower.endsWith(".nbt") || lower.endsWith(".json");
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
