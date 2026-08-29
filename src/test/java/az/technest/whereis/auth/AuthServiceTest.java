package az.technest.whereis.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import az.technest.whereis.auth.dto.LoginRequest;
import az.technest.whereis.auth.dto.RegisterRequest;
import az.technest.whereis.auth.dto.TokenPairResponse;
import az.technest.whereis.common.error.ApiException;
import az.technest.whereis.common.error.ConflictException;
import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.user.User;
import az.technest.whereis.user.UserRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdefghijklmnop";

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private RefreshTokenRevoker refreshTokenRevoker;

    // Low strength keeps the test fast; production wiring uses strength 12.
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final JwtProperties properties =
            new JwtProperties(SECRET, Duration.ofMinutes(15), Duration.ofDays(30));

    private AuthService authService;

    @BeforeEach
    void setUp() {
        JwtService jwtService =
                new JwtService(new NimbusJwtEncoder(new ImmutableSecret<>(properties.secretBytes())), properties);
        authService = new AuthService(userRepository, refreshTokenRepository, refreshTokenRevoker,
                encoder, jwtService, properties);
    }

    private User user(UUID id) {
        return User.builder().id(id).email("user@example.com").passwordHash(encoder.encode("password123")).build();
    }

    @Test
    void registerIssuesTokenPairAndStoresHashedRefreshToken() {
        UUID id = UUID.randomUUID();
        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenReturn(user(id));

        TokenPairResponse pair = authService.register(
                new RegisterRequest("User@Example.com", "password123", "Ada", "L"));

        assertThat(pair.accessToken()).isNotBlank();
        assertThat(pair.refreshToken()).isNotBlank();
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("user@example.com", "password123", null, null)))
                .isInstanceOf(ConflictException.class);
        verify(userRepository, never()).save(any());
    }

    @Test
    void loginRejectsUnknownEmailWithGenericError() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost@example.com", "whatever1")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    void loginRejectsWrongPasswordWithSameGenericError() {
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user(UUID.randomUUID())));

        assertThatThrownBy(() -> authService.login(new LoginRequest("user@example.com", "wrong-password")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    void refreshRotatesActiveToken() {
        UUID userId = UUID.randomUUID();
        RefreshToken stored = RefreshToken.builder()
                .userId(userId).tokenHash("h").expiresAt(Instant.now().plusSeconds(3600)).build();
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(stored));
        when(refreshTokenRepository.revokeActive(anyString(), any())).thenReturn(1);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId)));

        TokenPairResponse pair = authService.refresh("raw-refresh-token");

        assertThat(pair.accessToken()).isNotBlank();
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void reusedRefreshTokenRevokesTheWholeFamily() {
        UUID userId = UUID.randomUUID();
        RefreshToken revoked = RefreshToken.builder()
                .userId(userId).tokenHash("h")
                .expiresAt(Instant.now().plusSeconds(3600))
                .revokedAt(Instant.now().minusSeconds(60))
                .build();
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(revoked));
        when(refreshTokenRepository.revokeActive(anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> authService.refresh("stolen-token"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.TOKEN_INVALID));
        // Family revocation must go through the REQUIRES_NEW revoker so it survives the rollback.
        verify(refreshTokenRevoker).revokeFamily(eq(userId), any());
    }

    @Test
    void expiredRefreshTokenIsRejectedWithoutFamilyRevocation() {
        RefreshToken expired = RefreshToken.builder()
                .userId(UUID.randomUUID()).tokenHash("h")
                .expiresAt(Instant.now().minusSeconds(60))
                .build();
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));
        when(refreshTokenRepository.revokeActive(anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> authService.refresh("old-token"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.TOKEN_EXPIRED));
        verify(refreshTokenRevoker, never()).revokeFamily(any(), any());
    }

    @Test
    void unknownRefreshTokenIsRejected() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("unknown"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.TOKEN_INVALID));
    }
}
