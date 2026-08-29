package az.technest.whereis.auth.dto;

public record TokenPairResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresInSeconds
) {
}
