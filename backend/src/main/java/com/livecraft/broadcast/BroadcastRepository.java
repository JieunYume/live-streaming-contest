package com.livecraft.broadcast;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BroadcastRepository extends JpaRepository<Broadcast, Long> {

    @Query("select b from Broadcast b join fetch b.owner where b.id = :id and b.deleted = false")
    Optional<Broadcast> findActive(Long id);

    @Query("""
            select b from Broadcast b join fetch b.owner
            where b.deleted = false and (:status is null or b.status = :status)
            order by case when b.status = com.livecraft.broadcast.Broadcast.Status.LIVE then 0 else 1 end, b.createdAt desc
            """)
    List<Broadcast> findList(Broadcast.Status status);

    @Query("select b from Broadcast b join fetch b.owner where b.deleted = false and b.owner.id = :ownerId order by b.createdAt desc")
    List<Broadcast> findByOwner(Long ownerId);

    Optional<Broadcast> findByRoomName(String roomName);

    List<Broadcast> findByStatusAndPublisherLeftAtBefore(Broadcast.Status status, Instant before);
}
