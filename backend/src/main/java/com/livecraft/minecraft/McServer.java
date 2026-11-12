package com.livecraft.minecraft;

import java.time.Duration;
import java.time.Instant;

import com.livecraft.global.BaseTimeEntity;
import com.livecraft.global.Tokens;
import com.livecraft.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서버장이 등록한 마인크래프트 서버.
 * 연동 순서: 대시보드에서 연동 코드 발급 → 게임에서 /live link 코드 → 플러그인이 영구 토큰을 받아 저장 → 웹소켓 접속
 */
@Getter
@Entity
@Table(name = "mc_servers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class McServer extends BaseTimeEntity {

    private static final Duration LINK_CODE_TTL = Duration.ofMinutes(10);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(unique = true, length = 9)
    private String linkCode;

    private Instant linkCodeExpiresAt;

    @Column(unique = true, length = 64)
    private String pluginTokenHash;

    private Instant linkedAt;

    public McServer(User owner, String name) {
        this.owner = owner;
        this.name = name;
        newLinkCode();
    }

    public boolean isOwnedBy(Long userId) {
        return owner.getId().equals(userId);
    }

    public void rename(String name) {
        this.name = name;
    }

    public void newLinkCode() {
        linkCode = Tokens.humanCode();
        linkCodeExpiresAt = Instant.now().plus(LINK_CODE_TTL);
    }

    public boolean isLinkCodeValid(Instant now) {
        return linkCode != null && linkCodeExpiresAt != null && linkCodeExpiresAt.isAfter(now);
    }

    /** 코드를 소모하고 새 플러그인 토큰을 발급한다. 이전 토큰은 무효가 된다 @return 토큰 원문 */
    public String link() {
        String raw = Tokens.random(32);
        pluginTokenHash = Tokens.sha256Hex(raw);
        linkCode = null;
        linkCodeExpiresAt = null;
        linkedAt = Instant.now();
        return raw;
    }

    public boolean isLinked() {
        return pluginTokenHash != null;
    }
}
