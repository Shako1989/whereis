package az.technest.whereis.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import az.technest.whereis.common.error.ApiException;
import az.technest.whereis.common.error.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordVerifierTest {

    // Low strength keeps the test fast; production wiring uses strength 12.
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final PasswordVerifier verifier = new PasswordVerifier(encoder);

    private User user(String password) {
        return User.builder().id(UUID.randomUUID()).email("user@example.com")
                .passwordHash(encoder.encode(password)).build();
    }

    private static void assertUniform401(Throwable e) {
        ApiException api = (ApiException) e;
        assertThat(api.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(api.code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        assertThat(api.getMessage()).isEqualTo("Invalid credentials");
    }

    @Test
    void correctPasswordReturnsNormally() {
        assertThatCode(() -> verifier.requireMatch(user("password123"), "password123"))
                .doesNotThrowAnyException();
    }

    @Test
    void wrongPasswordIsRejectedWith401InvalidCredentials() {
        assertThatThrownBy(() -> verifier.requireMatch(user("password123"), "wrong-password"))
                .isInstanceOf(ApiException.class)
                .satisfies(PasswordVerifierTest::assertUniform401);
    }

    @Test
    void unknownUserGetsTheByteIdenticalAnswerAndStillPaysForABcryptComparison() {
        PasswordEncoder spied = spy(encoder);
        PasswordVerifier enumerationSafe = new PasswordVerifier(spied);

        assertThatThrownBy(() -> enumerationSafe.requireMatch(null, "whatever1"))
                .isInstanceOf(ApiException.class)
                .satisfies(PasswordVerifierTest::assertUniform401);
        // Timing equalizer: the dummy comparison ran against the start-up hash, so latency does
        // not reveal whether the account exists.
        verify(spied).matches(eq("whatever1"), anyString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void nullAndBlankPasswordsAre401NotA500FromBcrypt(String rawPassword) {
        assertThatThrownBy(() -> verifier.requireMatch(user("password123"), rawPassword))
                .isInstanceOf(ApiException.class)
                .satisfies(PasswordVerifierTest::assertUniform401);
        // The same holds when the account is missing too — no IllegalArgumentException leaks.
        assertThatThrownBy(() -> verifier.requireMatch(null, rawPassword))
                .isInstanceOf(ApiException.class)
                .satisfies(PasswordVerifierTest::assertUniform401);
    }
}
