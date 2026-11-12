package com.livecraft.minecraft;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GameActionRepository extends JpaRepository<GameAction, Long> {

    List<GameAction> findByServerIdAndStatusInOrderByIdAsc(Long serverId, Collection<GameAction.Status> statuses);

    List<GameAction> findTop50ByServerIdOrderByIdDesc(Long serverId);

    List<GameAction> findByDonationIdIn(Collection<Long> donationIds);

    /** 쿨타임 계산용: 같은 규칙·대상의 마지막 완료 시각 */
    @Query("""
            select max(a.doneAt) from GameAction a
            where a.server.id = :serverId and a.ruleId = :ruleId and lower(a.targetMcName) = :mcNameKey
              and a.status = com.livecraft.minecraft.GameAction.Status.DONE
            """)
    Optional<Instant> lastDoneAt(Long serverId, Long ruleId, String mcNameKey);

    @Query("""
            select count(a) from GameAction a
            where a.server.id = :serverId and a.ruleId = :ruleId and lower(a.targetMcName) = :mcNameKey
              and a.status in (com.livecraft.minecraft.GameAction.Status.PENDING, com.livecraft.minecraft.GameAction.Status.SENT)
            """)
    long countWaiting(Long serverId, Long ruleId, String mcNameKey);

    void deleteByServerId(Long serverId);
}
