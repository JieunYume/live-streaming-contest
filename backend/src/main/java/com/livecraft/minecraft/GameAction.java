package com.livecraft.minecraft;

import java.time.Instant;

import com.livecraft.global.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 플러그인에 보낼 게임 반응 대기열 (DB 테이블 = 메시지 큐).
 * PENDING → SENT → DONE / FAILED. 후원 1건당 최대 1개 (donationId UNIQUE).
 * 규칙·대상 정보를 복사해 두어, 서버장이 규칙을 바꿔도 이미 결제된 후원은 결제 당시 규칙대로 실행된다.
 */
@Getter
@Entity
@Table(name = "game_actions", indexes = @Index(columnList = "server_id, status"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GameAction extends BaseTimeEntity {

    public enum Status { PENDING, SENT, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_id")
    private McServer server;

    @Column(nullable = false, unique = true)
    private Long donationId;

    @Column(nullable = false)
    private Long broadcastId;

    /** 쿨타임 묶음 키: 같은 규칙 + 같은 대상끼리만 순서·간격을 지킨다 */
    @Column(nullable = false)
    private Long ruleId;

    @Column(nullable = false, length = 16)
    private String targetMcName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DonationRule.ActionType actionType;

    @Column(length = 200)
    private String param;

    @Column(nullable = false, length = 30)
    private String label;

    private int cooldownSeconds;

    @Column(nullable = false, length = 30)
    private String donorNickname;

    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    private int attempts;
    private Instant sentAt;
    private Instant doneAt;

    @Column(length = 200)
    private String resultMessage;

    public GameAction(McServer server, Long donationId, Long broadcastId, DonationRule rule, String targetMcName,
                      String donorNickname, long amount) {
        this.server = server;
        this.donationId = donationId;
        this.broadcastId = broadcastId;
        this.ruleId = rule.getId();
        this.actionType = rule.getActionType();
        this.param = rule.getParam();
        this.label = rule.getLabel();
        this.cooldownSeconds = rule.getCooldownSeconds();
        this.targetMcName = targetMcName;
        this.donorNickname = donorNickname;
        this.amount = amount;
        this.status = Status.PENDING;
    }

    public String groupKey() {
        return ruleId + ":" + McMember.key(targetMcName);
    }

    public void markSent() {
        status = Status.SENT;
        attempts++;
        sentAt = Instant.now();
    }

    /** @return 처음 완료 처리됐으면 true (같은 ACK 가 두 번 와도 한 번만 반영) */
    public boolean complete(boolean success, String message) {
        if (status == Status.DONE || status == Status.FAILED) return false;
        status = success ? Status.DONE : Status.FAILED;
        doneAt = Instant.now();
        resultMessage = message;
        return true;
    }

    public void giveUp(String message) {
        status = Status.FAILED;
        doneAt = Instant.now();
        resultMessage = message;
    }

    /** 서버장이 실패한 반응을 다시 실행 */
    public void retry() {
        status = Status.PENDING;
        attempts = 0;
        resultMessage = null;
    }
}
