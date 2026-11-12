package com.livecraft.plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BLOCKCAST 연동 플러그인
 * 1) 서버장이 게임에서 /blockcast link <코드> → 플랫폼에서 이 서버 전용 토큰을 받아 config.yml 에 저장
 * 2) 플러그인이 플랫폼에 웹소켓으로 먼저 접속 (서버장이 포트를 열 필요 없음)
 * 3) 접속/퇴장을 알리고, 후원 반응(ACTION)을 받아 실행한 뒤 결과(ACK)를 돌려준다
 */
public final class BlockcastPlugin extends JavaPlugin implements Listener {

    private PlatformConnection connection;
    private ActionExecutor executor;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        executor = new ActionExecutor(this);
        connection = new PlatformConnection(this, executor);
        getServer().getPluginManager().registerEvents(this, this);
        if (!token().isBlank()) connection.connect();
        else getLogger().info("아직 연동되지 않았어요. 플랫폼 대시보드의 코드로 /blockcast link <코드> 를 입력하세요.");
    }

    @Override
    public void onDisable() {
        if (connection != null) connection.shutdown();
    }

    String serverUrl() {
        String url = getConfig().getString("server-url", "http://localhost:8080");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    String token() {
        return getConfig().getString("token", "");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        connection.sendPresence(e.getPlayer().getName(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        connection.sendPresence(e.getPlayer().getName(), false);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return false;
        switch (args[0].toLowerCase()) {
            case "link" -> {
                if (args.length < 2) {
                    sender.sendMessage("§c사용법: /blockcast link <코드>");
                    return true;
                }
                link(sender, args[1]);
            }
            case "status" -> sender.sendMessage(connection.isOpen()
                    ? "§aBLOCKCAST 연결됨 (" + serverUrl() + ")"
                    : "§eBLOCKCAST 연결 안 됨" + (token().isBlank() ? " - 아직 연동 전이에요." : " - 재연결 중이에요."));
            case "reconnect" -> {
                connection.connect();
                sender.sendMessage("§e다시 연결하는 중…");
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** 연동 코드로 토큰 발급 (네트워크 호출은 비동기로) */
    private void link(CommandSender sender, String code) {
        sender.sendMessage("§e연동하는 중…");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("code", code);
                HttpRequest req = HttpRequest.newBuilder(URI.create(serverUrl() + "/api/plugin/link"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();
                HttpResponse<String> res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
                JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
                Bukkit.getScheduler().runTask(this, () -> {
                    if (res.statusCode() != 200) {
                        sender.sendMessage("§c연동 실패: " + (json.has("message") ? json.get("message").getAsString() : res.statusCode()));
                        return;
                    }
                    getConfig().set("token", json.get("token").getAsString());
                    saveConfig();
                    sender.sendMessage("§a연동 완료! 서버 이름: " + json.get("serverName").getAsString());
                    connection.connect();
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(this, () -> sender.sendMessage("§c연동 실패: 플랫폼에 연결할 수 없어요 (" + e.getMessage() + ")"));
            }
        });
    }
}
