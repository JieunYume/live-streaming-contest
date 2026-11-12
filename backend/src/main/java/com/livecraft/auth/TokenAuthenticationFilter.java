package com.livecraft.auth;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.livecraft.global.Tokens;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Authorization: Bearer {토큰} 을 DB 세션과 대조해 인증 정보를 채운다.
 * 토큰이 없거나 틀리면 그냥 비로그인으로 진행 (공개 API는 그대로 동작, 보호 API는 401).
 */
@Component
@RequiredArgsConstructor
public class TokenAuthenticationFilter extends OncePerRequestFilter {

    private final AuthTokenRepository authTokenRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String raw = header.substring(7).trim();
            authTokenRepository.findWithUser(Tokens.sha256Hex(raw))
                    .filter(t -> !t.isExpired(Instant.now()))
                    .ifPresent(t -> {
                        var u = t.getUser();
                        var principal = new AuthUser(u.getId(), u.getUsername(), u.getNickname());
                        var auth = new UsernamePasswordAuthenticationToken(principal, raw, List.of());
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    });
        }
        chain.doFilter(request, response);
    }
}
