package io.github.moxisuki.blockprint.link.bridge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tiny LRU stash used by the BG2 click-to-copy chat flow.
 *
 * <p>When a Building Gadgets 2 blueprint (.json) is uploaded via the
 * WebSocket bridge, the chat notification embeds a {@code ClickEvent}
 * with a {@code RUN_COMMAND} action pointing at
 * {@code /blockprintlink copy-bg2 <uuid>}. The bytes themselves don't
 * fit in a chat packet (a typical BG2 JSON is hundreds of KB), so they
 * ride here on the server side until the player clicks.
 *
 * <p>Each id is single-use ({@link #take(String)} removes it on read),
 * which gives "click once, then it's gone" semantics and naturally caps
 * memory. Entries that aren't clicked eventually fall out via LRU
 * eviction (capped at {@link #MAX_ENTRIES}) or when {@link #clear()} is
 * called from client shutdown.
 */
public final class Bg2ClipboardCache {

    /** Cap. BG2 templates are typically a few KB to a few hundred KB; 32 entries
     *  is plenty for any realistic click backlog and bounds worst-case memory
     *  to ~32 × 1 MB = 32 MB even if every upload hits the soft per-file cap. */
    public static final int MAX_ENTRIES = 32;

    /**
     * Soft per-file cap used by {@link SchematicsWsServer} before adding
     * to the cache. Anything bigger than this gets a chat notification
     * without a clickable suffix — defensive: prevents an accidentally
     * huge upload from pinning gigabytes of memory until the LRU evicts.
     */
    public static final int MAX_BYTES_PER_ENTRY = 1_048_576; // 1 MiB

    // accessOrder=true makes LinkedHashMap evict the LRU entry on insert
    // when size exceeds MAX_ENTRIES. Synchronized wrapper because multiple
    // WS handler threads can race with the click handler (which runs on
    // the client thread, but the cache lives in common / server-side).
    private static final Map<String, byte[]> STORAGE =
        Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                return size() > MAX_ENTRIES;
            }
        });

    private Bg2ClipboardCache() {}

    /**
     * Stash {@code bytes} under {@code id}, evicting the LRU entry if the
     * cache is full. No-op if id or bytes is null.
     */
    public static void put(String id, byte[] bytes) {
        if (id == null || bytes == null) return;
        // Defensive copy: the caller's array may be reused (e.g. local
        // variable goes out of scope), but we hold the only reference.
        STORAGE.put(id, bytes.clone());
    }

    /**
     * Atomically read and remove the entry for {@code id}. Returns
     * {@code null} if no entry exists (id unknown, already consumed,
     * or evicted by LRU).
     */
    public static byte[] take(String id) {
        if (id == null) return null;
        synchronized (STORAGE) {
            byte[] v = STORAGE.get(id);
            if (v != null) STORAGE.remove(id);
            return v;
        }
    }

    /** Diagnostic. Current entry count. */
    public static int size() {
        return STORAGE.size();
    }

    /** Drop everything. Call from client shutdown. */
    public static void clear() {
        STORAGE.clear();
    }

    // ── Server→Client clipboard bridge ──────────────────────────────
    //
    // RUN_COMMAND on Forge 1.20.1 routes through the server dispatcher
    // which can't call Minecraft.getInstance(). The server-side command
    // handler sets a pending id here; the client tick handler picks it
    // up and does the actual clipboard write on the render thread.

    private static volatile String pendingCopyId;

    /** Set by the server-side command handler. */
    public static void setPendingCopy(String id) {
        pendingCopyId = id;
    }

    /** Consumed by the client tick handler. Returns the id if pending. */
    public static String consumePendingCopyId() {
        String id = pendingCopyId;
        pendingCopyId = null;
        return id;
    }
}