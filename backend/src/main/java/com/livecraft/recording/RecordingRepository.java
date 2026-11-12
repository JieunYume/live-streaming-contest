package com.livecraft.recording;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface RecordingRepository extends JpaRepository<Recording, Long> {

    @Query("""
            select r from Recording r join fetch r.broadcast b join fetch b.owner
            where r.status = com.livecraft.recording.Recording.Status.READY and b.deleted = false
            order by r.id desc
            """)
    List<Recording> findReady();

    @Query("select r from Recording r join fetch r.broadcast b join fetch b.owner where r.id = :id and b.deleted = false")
    Optional<Recording> findWithBroadcast(Long id);

    // 같은 녹화에 조각이 동시에 들어와도 순서가 꼬이지 않게 행을 잠근다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Recording r join fetch r.broadcast b join fetch b.owner where r.id = :id")
    Optional<Recording> findForUpdate(Long id);

    List<Recording> findByBroadcastIdAndStatus(Long broadcastId, Recording.Status status);
}
