package com.livecraft.minecraft;

import com.livecraft.global.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서버 공통 후원 규칙: "minAmount 원 이상이면 이 반응". 후원 금액 이하 중 가장 큰 minAmount 규칙이 적용된다.
 * 예) 10,000 = 음식 / 30,000 = 랜덤박스 / 1,000,000 = 즉사
 */
@Getter
@Entity
@Table(name = "donation_rules", uniqueConstraints = @UniqueConstraint(columnNames = {"server_id", "min_amount"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DonationRule extends BaseTimeEntity {

    public enum ActionType {
        FOOD,        // 음식 지급 (param: 아이템 id, 기본 cooked_beef)
        RANDOM_BOX,  // 무작위 아이템 지급
        KILL,        // 즉사
        SPAWN_MOB,   // 몹 소환 (param: 몹 id, 기본 zombie)
        EFFECT,      // 포션 효과 (param: 효과 id, 기본 speed)
        COMMAND      // 서버장이 쓴 명령어 ({player} 가 대상 닉네임으로 바뀜)
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "server_id")
    private McServer server;

    @Column(nullable = false)
    private long minAmount;

    @Column(nullable = false, length = 30)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActionType actionType;

    @Column(length = 200)
    private String param;

    @Column(nullable = false)
    private int cooldownSeconds;

    public DonationRule(McServer server, long minAmount, String label, ActionType actionType, String param, int cooldownSeconds) {
        this.server = server;
        update(minAmount, label, actionType, param, cooldownSeconds);
    }

    public void update(long minAmount, String label, ActionType actionType, String param, int cooldownSeconds) {
        this.minAmount = minAmount;
        this.label = label;
        this.actionType = actionType;
        this.param = param;
        this.cooldownSeconds = cooldownSeconds;
    }
}
