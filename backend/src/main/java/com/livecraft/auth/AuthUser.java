package com.livecraft.auth;

/** 인증된 요청의 사용자 정보 (컨트롤러에서 @AuthenticationPrincipal 로 받는다) */
public record AuthUser(Long id, String username, String nickname) {}
