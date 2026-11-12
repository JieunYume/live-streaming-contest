package com.livecraft.plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 플랫폼과의 웹소켓 연결. 끊기면 5초 → 최대 60초 간격으로 다시 접속한다.
 * 다시 접속하면 현재 접속자 목록(HELLO)을 보내고, 서버는 그동안 쌓인 반응을 순서대로 다시 보낸다.
 */
final class PlatformConnection implements WebSocket.Listener {

    private final BlockcastPlugin plugin;
    private final ActionExecutor executor;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final StringBuilder buffer = new StringBuilder();
    private BukkitTask pingTask;
    private BukkitTask reconnectTask;
    private int backoffSeconds = 5;
    private volatile boolean stopped;

    PlatformConnection(BlockcastPlugin plugin, ActionExecutor executor) {
        this.plugin = plugin;
        this.executor = executor;
    }

    boolean isOpen() {
        WebSocket ws = socket.get();
        return ws != null && !ws.isOutputClosed();
    }

    void connect() {
        stopped = false;
        WebSocket old = socket.getAndSet(null);
        if (old != null) old.abort();
        String token = plugin.token();
        if (token.isBlank()) return;
        String wsUrl = plugin.serverUrl().replaceFirst("^http", "ws") + "/ws/plugin";
        http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(wsUrl), this)
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        plugin.getLogger().warning("플랫폼 연결 실패: " + err.getMessage());
                        scheduleReconnect();
                    }
                });
    }

    void shutdown() {
        stopped = true;
        if (pingTask != null) pingTask.cancel();
        if (reconnectTask != null) reconnectTask.cancel();
        WebSocket ws = socket.getAndSet(null);
        if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
    }

    void send(JsonObject msg) {
        WebSocket ws = socket.get();
        if (ws == null) return;
        synchronized (this) { // 웹소켓은 이전 전송이 끝나야 다음 전송 가능
            ws.sendText(msg.toString(), true).join();
        }
    }

    void sendPresence(String name, boolean online) {
        JsonObject m = new JsonObject();
        m.addProperty("type", "PRESENCE");
        m.addProperty("name", name);
        m.addProperty("online", online);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> send(m));
    }

    void sendAck(long actionId, boolean success, String message) {
        JsonObject m = new JsonObject();
        m.addProperty("type", "ACK");
        m.addProperty("actionId", actionId);
        m.addProperty("success", success);
        m.addProperty("message", message);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> send(m));
    }

    @Override
    public void onOpen(WebSocket ws) {
        socket.set(ws);
        backoffSeconds = 5;
        plugin.getLogger().info("BLOCKCAST 플랫폼에 연결됐어요.");
        // 접속자 목록은 메인 스레드에서 읽는다
        Bukkit.getScheduler().runTask(plugin, () -> {
            JsonArray players = new JsonArray();
            for (Player p : Bukkit.getOnlinePlayers()) players.add(p.getName());
            JsonObject hello = new JsonObject();
            hello.addProperty("type", "HELLO");
            hello.add("players", players);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> send(hello));
        });
        if (pingTask != null) pingTask.cancel();
        pingTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            JsonObject ping = new JsonObject();
            ping.addProperty("type", "PING");
            send(ping);
        }, 20L * 20, 20L * 20);
        ws.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        buffer.append(data);
        if (last) {
            String text = buffer.toString();
            buffer.setLength(0);
            try {
                JsonObject msg = JsonParser.parseString(text).getAsJsonObject();
                if ("ACTION".equals(msg.get("type").getAsString())) {
                    executor.execute(msg, this::sendAck);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("플랫폼 메시지 처리 실패: " + e.getMessage());
            }
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        socket.compareAndSet(ws, null);
        plugin.getLogger().info("플랫폼 연결 종료 (" + statusCode + " " + reason + ")");
        if (!"replaced".equals(reason) && !"unlinked".equals(reason)) scheduleReconnect();
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        socket.compareAndSet(ws, null);
        plugin.getLogger().warning("플랫폼 연결 오류: " + error.getMessage());
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (stopped || !plugin.isEnabled()) return;
        if (pingTask != null) pingTask.cancel();
        if (reconnectTask != null) reconnectTask.cancel();
        long delay = backoffSeconds;
        backoffSeconds = Math.min(backoffSeconds * 2, 60);
        reconnectTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::connect, 20L * delay);
    }
}
