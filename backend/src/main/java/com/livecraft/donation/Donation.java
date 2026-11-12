package com.livecraft.donation;

import java.time.Instant;

import com.livecraft.broadcast.Broadcast;
import com.livecraft.global.BaseTimeEntity;
import com.livecraft.user.User;

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
 * 후원 1건. 결제창을 열기 전에 PENDING 으로 만들어 금액·대상을 서버에 고정해 두고,
 * 승인 결과가 오면 서버에 저장된 금액·대상과 대조한 뒤 PAID 로 확정한다.
 * orderId·paymentKey 가 UNIQUE 라서 같은 결제가 두 번 적립될 수 없다.
 */
@Getter
@Entity
@Table(name = "donations", indexes = @Index(columnList = "broadcast_id, status"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Donation extends BaseTimeEntity {

    public enum Status { PENDING, PAID, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String orderId;

    @Column(unique = true, length = 200)
    private String paymentKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "broadcast_id")
    private Broadcast broadcast;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "donor_id")
    private User donor;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "streamer_id")
    private User streamer;

    @Column(nullable = false)
    private long amount;

    @Column(length = 100)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(length = 200)
    private String failReason;

    private Instant paidAt;

    public Donation(String orderId, Broadcast broadcast, User donor, User streamer, long amount, String message) {
        this.orderId = orderId;
        this.broadcast = broadcast;
        this.donor = donor;
        this.streamer = streamer;
        this.amount = amount;
        this.message = message;
        this.status = Status.PENDING;
    }

    /** @return 이번 호출로 처음 확정됐으면 true. 이미 PAID 면 false (중복 승인 통지) */
    public boolean markPaid(String paymentKey) {
        if (status == Status.PAID) return false;
        this.status = Status.PAID;
        this.paymentKey = paymentKey;
        this.paidAt = Instant.now();
        this.failReason = null;
        return true;
    }

    public void markFailed(String reason) {
        if (status == Status.PENDING) {
            status = Status.FAILED;
            failReason = reason;
        }
    }
}
