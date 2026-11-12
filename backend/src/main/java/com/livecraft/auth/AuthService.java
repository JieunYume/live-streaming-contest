package com.livecraft.auth;

import java.time.Duration;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.livecraft.global.AppProperties;
import com.livecraft.global.Tokens;
import com.livecraft.global.error.ApiException;
import com.livecraft.user.User;
import com.livecraft.user.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;

    public record LoginResult(String token, AuthUser user) {}

    @Transactional
    public AuthUser signup(String username, String password, String nickname) {
        if (userRepository.existsByUsername(username)) {
            throw ApiException.conflict("DUPLICATE_USERNAME", "이미 사용 중인 아이디입니다.");
        }
        User user = userRepository.save(new User(username, passwordEncoder.encode(password), nickname));
        return toAuthUser(user);
    }

    @Transactional
    public LoginResult login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .filter(u -> passwordEncoder.matches(password, u.getPasswordHash()))
                // 아이디가 없는지 비밀번호가 틀렸는지 구분해 알려주지 않는다
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "아이디 또는 비밀번호가 올바르지 않습니다."));
        return issue(user);
    }

    /** 심사용: 비밀번호 입력 없이 시드된 테스트 계정으로 로그인 */
    @Transactional
    public LoginResult testLogin(String username) {
        if (!props.auth().testLoginEnabled() || !TestAccounts.isTestAccount(username)) {
            throw ApiException.forbidden("테스트 로그인을 사용할 수 없습니다.");
        }
        User user = userRepository.findByUsername(username).orElseThrow(() -> ApiException.notFound("테스트 계정"));
        return issue(user);
    }

    @Transactional
    public void logout(String rawToken) {
        authTokenRepository.deleteById(Tokens.sha256Hex(rawToken));
    }

    private LoginResult issue(User user) {
        String raw = Tokens.random(32);
        Instant expiresAt = Instant.now().plus(Duration.ofHours(props.auth().tokenTtlHours()));
        authTokenRepository.save(new AuthToken(Tokens.sha256Hex(raw), user, expiresAt));
        return new LoginResult(raw, toAuthUser(user));
    }

    private static AuthUser toAuthUser(User u) {
        return new AuthUser(u.getId(), u.getUsername(), u.getNickname());
    }
}
