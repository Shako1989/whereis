package az.technest.whereis.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Atomic rotation step: revokes the token only if it is still active.
     * Returns 0 when the token was already revoked or expired — the caller decides which.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken r set r.revokedAt = :now
            where r.tokenHash = :tokenHash and r.revokedAt is null and r.expiresAt > :now
            """)
    int revokeActive(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken r set r.revokedAt = :now
            where r.userId = :userId and r.revokedAt is null
            """)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
