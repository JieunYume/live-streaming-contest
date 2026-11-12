package com.livecraft.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AuthTokenRepository extends JpaRepository<AuthToken, String> {

    @Query("select t from AuthToken t join fetch t.user where t.tokenHash = :hash")
    Optional<AuthToken> findWithUser(String hash);

    @Modifying
    @Query("delete from AuthToken t where t.expiresAt < :now")
    int deleteExpired(Instant now);
}
