package com.livecraft.minecraft.plugin;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.livecraft.minecraft.GameActionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 플러그인 ↔ 서버 메시지
 * 받는 것: HELLO{players}, PRESENCE{name, online}, ACK{actionId, success, message}, PING
 * 보내는 것: ACTION{...} (GameActionService), PONG
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PluginWebSocketHandler extends TextWebSocketHandler {

    public static final String SERVER_ID_ATTR = "serverId";

    private final PluginSessionRegistry registry;
    private final GameActionService gameActionService;
    private final JsonMapper jsonMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long serverId = serverId(session);
        registry.register(serverId, session);
        log.info("플러그인 연결 server={}", serverId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long serverId = serverId(session);
        registry.touch(serverId);
        JsonNode msg;
        try {
            msg = jsonMapper.readTree(message.getPayload());
        } catch (RuntimeException e) {
            log.warn("플러그인 메시지 해석 실패 server={}", serverId);
            return;
        }
        switch (msg.path("type").asString()) {
            case "HELLO" -> {
                List<String> names = new ArrayList<>();
                msg.path("players").forEach(p -> names.add(p.isString() ? p.asString() : p.path("name").asString()));
                registry.setPlayers(serverId, names);
            }
            case "PRESENCE" -> registry.setOnline(serverId, msg.path("name").asString(), msg.path("online").asBoolean());
            case "ACK" -> gameActionService.ack(serverId, msg.path("actionId").asLong(), msg.path("success").asBoolean(),
                    msg.path("message").asString(""));
            case "PING" -> registry.send(serverId, "{\"type\":\"PONG\"}");
            default -> log.debug("알 수 없는 플러그인 메시지 {}", msg.path("type").asString());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long serverId = serverId(session);
        registry.unregister(serverId, session);
        log.info("플러그인 연결 종료 server={} {}", serverId, status);
    }

    private static Long serverId(WebSocketSession session) {
        return (Long) session.getAttributes().get(SERVER_ID_ATTR);
    }
}
