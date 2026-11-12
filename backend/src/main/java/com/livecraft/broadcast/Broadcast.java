package com.livecraft.broadcast;

import java.time.Instant;

import com.livecraft.global.BaseTimeEntity;
import com.livecraft.global.error.ApiException;
import com.livecraft.user.User;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방송 1건 = 송출 1회. 상태는 한 방향으로만 바뀐다: READY(대기) → LIVE(방송 중) → ENDED(종료)
 * 종료된 방송은 다시 켤 수 없고, 새 방송을 만들어야 한다 (다시보기·후원 내역이 방송 단위로 남게).
 */
@Getter
@Entity
@Table(name = "broadcasts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Broadcast extends BaseTimeEntity {

    public enum Status { READY, LIVE, ENDED }

    public enum SourceType { SCREEN, CAMERA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id")
    private User owner;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private SourceType sourceType;

    @Column(nullable = false, unique = true, length = 64)
    private String roomName;

    private Instant startedAt;
    private Instant endedAt;

    /** 송출자 연결이 끊긴 시각. 유예 시간 안에 다시 들어오면 null 로 돌아간다 */
    private Instant publisherLeftAt;

    @Column(nullable = false)
    private boolean deleted;

    public Broadcast(User owner, String title, String description, String roomName) {
        this.owner = owner;
        this.title = title;
        this.description = description;
        this.roomName = roomName;
        this.status = Status.READY;
    }

    public boolean isOwnedBy(Long userId) {
        return owner.getId().equals(userId);
    }

    public void update(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public void goLive(SourceType sourceType) {
        if (status == Status.ENDED) {
            throw ApiException.conflict("BROADCAST_ENDED", "이미 종료된 방송입니다. 새 방송을 만들어 주세요.");
        }
        if (status == Status.READY) {
            startedAt = Instant.now();
        }
        this.status = Status.LIVE;
        this.sourceType = sourceType;
        this.publisherLeftAt = null;
    }

    /** @return 실제로 상태가 바뀌었으면 true (이미 종료면 false) */
    public boolean end() {
        if (status == Status.ENDED) return false;
        status = Status.ENDED;
        endedAt = Instant.now();
        publisherLeftAt = null;
        return true;
    }

    public void publisherLeft() {
        if (status == Status.LIVE) publisherLeftAt = Instant.now();
    }

    public void publisherJoined() {
        publisherLeftAt = null;
    }

    public void delete() {
        deleted = true;
    }

    public String publisherIdentity() {
        return "owner-" + owner.getId();
    }
}
