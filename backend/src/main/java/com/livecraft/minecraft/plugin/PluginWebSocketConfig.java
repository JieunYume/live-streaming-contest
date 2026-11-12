package com.livecraft.minecraft.plugin;

import java.util.Map;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import com.livecraft.global.Tokens;
import com.livecraft.minecraft.McServerRepository;

import lombok.RequiredArgsConstructor;

/** /ws/plugin : 플러그인 토큰(Authorization: Bearer 또는 ?token=)이 맞아야 연결된다 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class PluginWebSocketConfig implements WebSocketConfigurer {

    private final PluginWebSocketHandler handler;
    private final McServerRepository serverRepository;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/plugin")
                .addInterceptors(new TokenInterceptor())
                .setAllowedOrigins("*"); // 브라우저가 아닌 게임 서버가 접속하므로 Origin 제한 의미 없음 (토큰으로 인증)
    }

    private class TokenInterceptor implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                       Map<String, Object> attributes) {
            String token = null;
            String header = request.getHeaders().getFirst("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                token = header.substring(7).trim();
            } else {
                token = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("token");
            }
            if (token == null || token.isBlank()) {
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }
            var server = serverRepository.findByPluginTokenHash(Tokens.sha256Hex(token));
            if (server.isEmpty()) {
                response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
                return false;
            }
            attributes.put(PluginWebSocketHandler.SERVER_ID_ATTR, server.get().getId());
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception ex) {
        }
    }
}
