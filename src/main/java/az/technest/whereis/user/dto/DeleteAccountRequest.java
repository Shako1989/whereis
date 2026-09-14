package az.technest.whereis.user.dto;

/**
 * Re-authentication payload for {@code DELETE /api/v1/users/me}.
 *
 * <p>Deliberately carries no bean-validation annotations: a missing or blank password must be
 * answered with the same {@code 401 INVALID_CREDENTIALS} as a wrong one, never with a
 * {@code 400 VALIDATION_ERROR} that would tell a caller which shape the server expected.
 */
public record DeleteAccountRequest(String password) {
}
