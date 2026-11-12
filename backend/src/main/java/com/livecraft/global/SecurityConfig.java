package com.livecraft.global;

import java.util.List;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.livecraft.auth.TokenAuthenticationFilter;

/**
 * 인증·인가 규칙
 * - 공개: 방송 목록/상세/시청 토큰/실시간 이벤트, 댓글·후원 조회, 녹화 조회, 회원가입/로그인, 외부 웹훅, 플러그인 연동
 * - 그 외 /api/** 는 로그인 필요 (소유자·작성자 확인은 각 서비스에서 한다)
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TokenAuthenticationFilter tokenFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // 쿠키가 아닌 Authorization 헤더 인증이라 CSRF 대상 아님
                .cors(c -> {})
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/broadcasts/mine").authenticated() // 아래 "/api/broadcasts/*" 공개 규칙보다 먼저
                        .requestMatchers("/api/auth/signup", "/api/auth/login", "/api/auth/test-login", "/api/auth/test-accounts").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/broadcasts", "/api/broadcasts/*", "/api/broadcasts/*/viewer-token",
                                "/api/broadcasts/*/events", "/api/broadcasts/*/comments",
                                "/api/broadcasts/*/donations", "/api/broadcasts/*/donation-options",
                                "/api/recordings", "/api/recordings/*", "/api/recordings/*/video").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/livekit/webhook", "/api/payments/toss/webhook", "/api/plugin/link").permitAll()
                        .requestMatchers("/ws/plugin").permitAll() // 플러그인 토큰은 웹소켓 핸드셰이크에서 따로 검증
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(HttpStatus.UNAUTHORIZED.value());
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"로그인이 필요합니다.\"}");
                }))
                .addFilterBefore(tokenFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    // 필터를 @Component 로 만들면 서블릿 필터로도 자동 등록돼 두 번 돈다. 시큐리티 체인 안에서만 돌게 막는다.
    @Bean
    FilterRegistrationBean<TokenAuthenticationFilter> disableAutoRegistration(TokenAuthenticationFilter filter) {
        var bean = new FilterRegistrationBean<>(filter);
        bean.setEnabled(false);
        return bean;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(props.frontendUrl()));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
