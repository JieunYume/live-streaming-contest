package com.livecraft.recording;

import java.time.Instant;

import com.livecraft.broadcast.Broadcast;
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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 녹화 영상 1개. 송출자 브라우저가 MediaRecorder 로 만든 조각을 순서대로 이어 붙여 파일 하나로 저장한다.
 * filePath 는 저장소 기준 상대 경로라 저장 위치(로컬 → R2 등)를 바꿔도 DB 값은 그대로 쓴다.
 */
@Getter
@Entity
@Table(name = "recordings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recording extends BaseTimeEntity {

    public enum Status { RECORDING, READY, EMPTY }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "broadcast_id")
    private Broadcast broadcast;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(nullable = false, length = 200)
    private String filePath;

    @Column(nullable = false, length = 100)
    private String mimeType;

    private long sizeBytes;

    /** 다음에 받아야 할 조각 번호 (재전송된 조각을 두 번 붙이지 않기 위해) */
    private int nextSeq;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant endedAt;

    public Recording(Broadcast broadcast, String filePath, String mimeType) {
        this.broadcast = broadcast;
        this.filePath = filePath;
        this.mimeType = mimeType;
        this.status = Status.RECORDING;
        this.startedAt = Instant.now();
    }

    public void appended(long bytes) {
        sizeBytes += bytes;
        nextSeq++;
    }

    public void finish() {
        if (status != Status.RECORDING) return;
        status = sizeBytes > 0 ? Status.READY : Status.EMPTY;
        endedAt = Instant.now();
    }
}
