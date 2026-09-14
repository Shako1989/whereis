package az.technest.whereis.user;

import az.technest.whereis.common.security.CurrentUser;
import az.technest.whereis.user.dto.DeleteAccountRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own account. Lives under {@code /users}, NOT {@code /auth}: the auth filter chain
 * runs without the JWT decoder, so a request there would arrive anonymous and
 * {@link CurrentUser#id()} could not resolve the subject.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserAccountController {

    private final AccountDeletionService accountDeletionService;

    /**
     * Google Play's in-app account deletion. The body is optional on purpose: {@code {}}, an empty
     * body and a blank password all funnel into the single 401 the service produces, instead of a
     * 400 that would describe the expected shape.
     */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe(@RequestBody(required = false) DeleteAccountRequest request) {
        accountDeletionService.deleteOwnAccount(CurrentUser.id(), request == null ? null : request.password());
    }
}
