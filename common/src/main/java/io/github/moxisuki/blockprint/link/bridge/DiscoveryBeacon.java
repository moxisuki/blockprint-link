package io.github.moxisuki.blockprint.link.bridge;

import io.github.moxisuki.blockprint.link.LogUtil;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DiscoveryBeacon {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "blockprintlink-discovery"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private DatagramSocket socket;
    private ScheduledFuture<?> task;

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            task = scheduler.scheduleAtFixedRate(this::broadcastOnce, 0, BridgeConfig.DISCOVERY_INTERVAL_MS, TimeUnit.MILLISECONDS);
            LogUtil.info("UDP discovery beacon on :" + BridgeConfig.discoveryPort());
        } catch (Exception e) {
            LogUtil.error("Failed to start discovery beacon", e);
            running.set(false);
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (task != null) task.cancel(false);
        if (socket != null) socket.close();
        scheduler.shutdownNow();
    }

    private void broadcastOnce() {
        try {
            String json = "{\"type\":\"blockprintlink/discovery\",\"version\":\"0.1.0\",\"wsPort\":" + BridgeConfig.wsPort() + ",\"tokenHint\":\"" + BridgeConfig.tokenHint() + "\"}";
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), BridgeConfig.discoveryPort()));
        } catch (Exception ignored) {}
    }
}
