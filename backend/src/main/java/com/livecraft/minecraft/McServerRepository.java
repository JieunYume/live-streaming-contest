package com.livecraft.minecraft;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface McServerRepository extends JpaRepository<McServer, Long> {

    @Query("select s from McServer s join fetch s.owner where s.owner.id = :ownerId order by s.id")
    List<McServer> findByOwner(Long ownerId);

    Optional<McServer> findByLinkCode(String linkCode);

    Optional<McServer> findByPluginTokenHash(String pluginTokenHash);
}
