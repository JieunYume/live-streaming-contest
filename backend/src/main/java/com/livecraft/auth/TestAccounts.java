package com.livecraft.auth;

import java.time.Instant;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.livecraft.global.AppProperties;
import com.livecraft.user.User;
import com.livecraft.user.UserRepository;

import lombok.RequiredArgsConstructor;

/** 심사용 테스트 계정을 앱 시작 시 만들어 둔다 (이미 있으면 건너뜀) */
@Component
@RequiredArgsConstructor
public class TestAccounts implements ApplicationRunner {

    public record Account(String username, String nickname, String role) {}

    public static final List<Account> ACCOUNTS = List.of(
            new Account("streamer1", "스트리머1", "방송 소유자 · 마크 서버장"),
            new Account("streamer2", "스트리머2", "다른 스트리머 (권한 거절 확인용)"),
            new Account("viewer1", "시청자1", "시청자 · 후원자"));

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;

    public static boolean isTestAccount(String username) {
        return ACCOUNTS.stream().anyMatch(a -> a.username().equals(username));
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (Account a : ACCOUNTS) {
            if (!userRepository.existsByUsername(a.username())) {
                userRepository.save(new User(a.username(), passwordEncoder.encode(props.auth().testPassword()), a.nickname()));
            }
        }
    }

    @Scheduled(fixedDelayString = "PT1H")
    @Transactional
    public void cleanExpiredTokens() {
        authTokenRepository.deleteExpired(Instant.now());
    }
}
