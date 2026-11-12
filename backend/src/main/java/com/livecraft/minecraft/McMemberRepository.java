package com.livecraft.minecraft;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface McMemberRepository extends JpaRepository<McMember, Long> {

    @Query("select m from McMember m join fetch m.user where m.server.id = :serverId order by m.id")
    List<McMember> findByServer(Long serverId);

    @Query("select m from McMember m join fetch m.server s join fetch s.owner where m.user.id = :userId order by m.id")
    List<McMember> findByUser(Long userId);

    @Query("select m from McMember m join fetch m.server where m.user.id = :userId and m.status = com.livecraft.minecraft.McMember.Status.ACCEPTED")
    Optional<McMember> findAccepted(Long userId);

    boolean existsByServerIdAndMcNameKey(Long serverId, String mcNameKey);

    void deleteByServerId(Long serverId);
}
