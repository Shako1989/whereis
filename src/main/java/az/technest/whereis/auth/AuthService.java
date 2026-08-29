package az.technest.whereis.auth;

import az.technest.whereis.auth.dto.LoginRequest;
import az.technest.whereis.auth.dto.RegisterRequest;
import az.technest.whereis.auth.dto.TokenPairResponse;
import az.technest.whereis.common.error.ApiException;
import az.technest.whereis.common.error.ConflictException;
import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.common.util.Names;
import az.technest.whereis.user.User;
import az.technest.whereis.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenRevoker refreshTokenRevoker;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProperties;
    private final String timingEqualizerHash;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       RefreshTokenRevoker refreshTokenRevoker,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenRevoker = refreshTokenRevoker;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProperties = jwtProperties;
        this.timingEqualizerHash = passwordEncoder.encode("timing-equalizer-" + RANDOM.nextLong());
    }

    @Transactional
    public TokenPairResponse register(RegisterRequest request) {
        String email = Names.normalize(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException(ErrorCode.EMAIL_IN_USE, "Email is already registered");
        }
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .firstName(Names.clean(request.firstName()))
                .lastName(Names.clean(request.lastName()))
                .build();
        user = userRepository.save(user);
        return issuePair(user);
    }

    @Transactional
    public TokenPairResponse login(LoginRequest request) {
        String email = Names.normalize(request.email());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            // Equalize timing so login latency does not reveal whether the email exists.
            passwordEncoder.matches(request.password(), timingEqualizerHash);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        return issuePair(user);
    }

    @Transactional
    public TokenPairResponse refresh(String rawRefreshToken) {
        String tokenHash = sha256Hex(rawRefreshToken);
        RefreshToken token = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> unauthorized(ErrorCode.TOKEN_INVALID, "Unknown refresh token"));
        Instant now = Instant.now();
        int rotated = refreshTokenRepository.revokeActive(tokenHash, now);
        if (rotated == 0) {
            RefreshToken fresh = refreshTokenRepository.findByTokenHash(tokenHash).orElse(token);
            if (fresh.getRevokedAt() != null) {
                // Reuse of a rotated token: assume theft, revoke the whole family.
                // REQUIRES_NEW — the 401 below rolls this transaction back, and the
                // revocation must survive that rollback.
                refreshTokenRevoker.revokeFamily(fresh.getUserId(), now);
                throw unauthorized(ErrorCode.TOKEN_INVALID, "Refresh token reuse detected");
            }
            throw unauthorized(ErrorCode.TOKEN_EXPIRED, "Refresh token expired");
        }
        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> unauthorized(ErrorCode.TOKEN_INVALID, "Unknown refresh token"));
        return issuePair(user);
    }

    private TokenPairResponse issuePair(User user) {
        String accessToken = jwtService.createAccessToken(user);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        refreshTokenRepository.save(RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(sha256Hex(refreshToken))
                .expiresAt(Instant.now().plus(jwtProperties.refreshTtl()))
                .build());
        return new TokenPairResponse(accessToken, refreshToken, "Bearer",
                jwtProperties.accessTtl().toSeconds());
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS, "Invalid credentials");
    }

    private static ApiException unauthorized(ErrorCode code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }
}
