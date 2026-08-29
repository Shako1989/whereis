package az.technest.whereis.auth;

import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Family revocation must survive the 401 thrown right after it: the caller's transaction
 * rolls back on that exception, so the revocation runs in its own committed transaction.
 * Separate bean — a REQUIRES_NEW method on AuthService itself would be a self-invocation no-op.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenRevoker {

    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamily(UUID userId, Instant now) {
        refreshTokenRepository.revokeAllForUser(userId, now);
    }
}
