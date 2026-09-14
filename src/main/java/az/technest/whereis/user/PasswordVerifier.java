package az.technest.whereis.user;

import az.technest.whereis.common.error.ApiException;
import az.technest.whereis.common.error.ErrorCode;
import java.security.SecureRandom;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * The ONE place a stored password hash is ever compared against a candidate. Login and account
 * deletion both re-authenticate through here, so the enumeration-safety properties live in a
 * single implementation.
 *
 * <p>Every call performs exactly one BCrypt comparison regardless of which branch rejects it:
 * an unknown account is compared against a throw-away hash generated at start-up, so response
 * latency does not reveal whether the account exists. All rejections produce the byte-identical
 * {@code 401 INVALID_CREDENTIALS}.
 *
 * <p>Lives in {@code user/} rather than {@code auth/} because the hash is a column on
 * {@link User}, and because {@code auth/} already depends on this package — placing the verifier
 * there would force {@code user/} to depend back on {@code auth/}.
 */
@Component
public class PasswordVerifier {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasswordEncoder passwordEncoder;
    private final String timingEqualizerHash;

    public PasswordVerifier(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
        this.timingEqualizerHash = passwordEncoder.encode("timing-equalizer-" + RANDOM.nextLong());
    }

    /**
     * Throws {@code 401 INVALID_CREDENTIALS} unless {@code rawPassword} matches the user's hash.
     *
     * @param user        the account being authenticated, or {@code null} when no such account exists
     * @param rawPassword the candidate, or {@code null}/blank when the client sent none
     */
    public void requireMatch(User user, String rawPassword) {
        // BCrypt rejects a null candidate with an IllegalArgumentException, which would surface as
        // a 500; an empty probe keeps the dummy comparison running so timing stays uniform.
        String candidate = rawPassword == null ? "" : rawPassword;
        String hash = user == null ? timingEqualizerHash : user.getPasswordHash();
        boolean matches = passwordEncoder.matches(candidate, hash);
        if (user == null || rawPassword == null || rawPassword.isBlank() || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS, "Invalid credentials");
        }
    }
}
