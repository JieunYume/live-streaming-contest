package com.livecraft.auth;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.livecraft.global.AppProperties;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final AppProperties props;

    public record SignupRequest(
            @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_]{4,20}$", message = "영문·숫자·_ 4~20자") String username,
            @NotBlank @Size(min = 8, max = 64) String password,
            @NotBlank @Size(max = 20) String nickname) {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record TestLoginRequest(@NotBlank String username) {}

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.LoginResult signup(@Valid @RequestBody SignupRequest req) {
        authService.signup(req.username(), req.password(), req.nickname());
        return authService.login(req.username(), req.password()); // 가입 후 바로 로그인
    }

    @PostMapping("/login")
    public AuthService.LoginResult login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req.username(), req.password());
    }

    @GetMapping("/test-accounts")
    public List<TestAccounts.Account> testAccounts() {
        return props.auth().testLoginEnabled() ? TestAccounts.ACCOUNTS : List.of();
    }

    @PostMapping("/test-login")
    public AuthService.LoginResult testLogin(@Valid @RequestBody TestLoginRequest req) {
        return authService.testLogin(req.username());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader("Authorization") String authorization) {
        authService.logout(authorization.substring(7).trim());
    }

    @GetMapping("/me")
    public AuthUser me(@AuthenticationPrincipal AuthUser user) {
        return user;
    }
}
