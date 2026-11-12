package com.livecraft.minecraft.plugin;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import com.livecraft.minecraft.McMember;

import lombok.extern.slf4j.Slf4j;

/**
 * 접속 중인 플러그인 세션과 각 서버의 접속 플레이어 목록.
 * shortcut: 메모리에 둔다. 백엔드를 여러 대로 늘리면 Redis 로 옮긴다 (지금은 서버 1대 전제).
 */
@Slf4j
@Component
public class PluginSessionRegistry {

    private static final long STALE_SECONDS = 60;

    private record Conn(WebSocketSession session, Instant connectedAt) {}

    private final Map<Long, Conn> sessions = new ConcurrentHashMap<>();
    private final Map<Long, Instant> lastSeen = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, String>> onlinePlayers = new ConcurrentHashMap<>();

    /** 같은 서버에서 새로 접속하면 이전 연결은 끊는다 (플러그인 재시작·재연동) */
    public void register(Long serverId, WebSocketSession raw) {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 10_000, 512 * 1024);
        Conn old = sessions.put(serverId, new Conn(session, Instant.now()));
        touch(serverId);
        onlinePlayers.put(serverId, new ConcurrentHashMap<>());
        if (old != null) closeQuietly(old.session(), CloseStatus.POLICY_VIOLATION.withReason("replaced"));
    }

    public void unregister(Long serverId, WebSocketSession raw) {
        sessions.computeIfPresent(serverId, (id, conn) -> {
            WebSocketSession s = conn.session();
            boolean same = s.getId().equals(raw.getId());
            if (same) {
                onlinePlayers.remove(serverId);
                lastSeen.remove(serverId);
            }
            return same ? null : conn;
        });
    }

    /** 연동 해제·서버 삭제 시 강제로 끊는다 */
    public void disconnect(Long serverId) {
        Conn conn = sessions.remove(serverId);
        onlinePlayers.remove(serverId);
        if (conn != null) closeQuietly(conn.session(), CloseStatus.NORMAL.withReason("unlinked"));
    }

    public void touch(Long serverId) {
        lastSeen.put(serverId, Instant.now());
    }

    public boolean isConnected(Long serverId) {
        return sessions.containsKey(serverId);
    }

    public Collection<Long> connectedServerIds() {
        return List.copyOf(sessions.keySet());
    }

    public boolean send(Long serverId, String json) {
        Conn conn = sessions.get(serverId);
        if (conn == null) return false;
        try {
            conn.session().sendMessage(new TextMessage(json));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.warn("플러그인 전송 실패 server={}: {}", serverId, e.getMessage());
            return false;
        }
    }

    public void setPlayers(Long serverId, Collection<String> names) {
        Map<String, String> map = new ConcurrentHashMap<>();
        names.forEach(n -> map.put(McMember.key(n), n));
        onlinePlayers.put(serverId, map);
    }

    public void setOnline(Long serverId, String name, boolean online) {
        Map<String, String> map = onlinePlayers.computeIfAbsent(serverId, k -> new ConcurrentHashMap<>());
        if (online) map.put(McMember.key(name), name);
        else map.remove(McMember.key(name));
    }

    public boolean isOnline(Long serverId, String mcName) {
        Map<String, String> map = onlinePlayers.get(serverId);
        return map != null && map.containsKey(McMember.key(mcName));
    }

    public Set<String> onlinePlayers(Long serverId) {
        Map<String, String> map = onlinePlayers.get(serverId);
        return map == null ? Set.of() : Set.copyOf(map.values());
    }

    /** 응답이 끊긴 연결 정리 (플러그인은 20초마다 PING 을 보낸다) */
    @Scheduled(fixedDelay = 15_000)
    public void closeStale() {
        Instant limit = Instant.now().minusSeconds(STALE_SECONDS);
        sessions.forEach((serverId, conn) -> {
            Instant seen = lastSeen.get(serverId);
            if (seen != null && seen.isBefore(limit)) {
                log.info("플러그인 응답 없음, 연결 종료 server={}", serverId);
                disconnect(serverId);
            }
        });
    }

    private static void closeQuietly(WebSocketSession s, CloseStatus status) {
        try {
            s.close(status);
        } catch (IOException ignored) {
            // 이미 끊긴 연결
        }
    }
}
