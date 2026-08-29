package az.technest.whereis.common.security;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Resolves the authenticated user's id from the security context.
 * The client-supplied userId is never trusted; ownership always derives from the JWT subject.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            return UUID.fromString(jwtAuth.getToken().getSubject());
        }
        throw new IllegalStateException("No authenticated user in security context");
    }
}
