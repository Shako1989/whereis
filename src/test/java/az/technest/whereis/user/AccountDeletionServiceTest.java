package az.technest.whereis.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import az.technest.whereis.common.error.ApiException;
import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.item.ItemDeletionSummary;
import az.technest.whereis.item.ItemService;
import az.technest.whereis.location.LocationService;
import az.technest.whereis.space.SpaceService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AccountDeletionServiceTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @Mock
    private UserRepository userRepository;
    @Mock
    private SpaceService spaceService;
    @Mock
    private LocationService locationService;
    @Mock
    private ItemService itemService;
    private AccountDeletionService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Real verifier, not a mock: the test must prove the service refuses on a genuine BCrypt mismatch.
        service = new AccountDeletionService(userRepository, new PasswordVerifier(encoder),
                spaceService, locationService, itemService);
    }

    private User user() {
        return User.builder().id(userId).email("user@example.com")
                .passwordHash(encoder.encode("password123")).build();
    }

    @Test
    void wrongPasswordIs401AndTouchesNothing() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(user()));

        assertThatThrownBy(() -> service.deleteOwnAccount(userId, "wrong-password"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                });
        verifyNoInteractions(spaceService, locationService, itemService);
        verify(userRepository, never()).delete(any());
    }

    @Test
    void happyPathRunsTheCascadeInTheOnlyOrderTheForeignKeysAllow() {
        User user = user();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(spaceService.lockAllSpacesOfUser(userId)).thenReturn(List.of(UUID.randomUUID()));
        when(itemService.deleteAllForUser(userId)).thenReturn(new ItemDeletionSummary(3, 4));
        when(locationService.deleteAllForUser(userId)).thenReturn(5);
        when(spaceService.deleteAllForUser(userId)).thenReturn(1);

        service.deleteOwnAccount(userId, "password123");

        // Locks first (no writer can interleave); items before locations (RESTRICT); locations
        // before spaces (a space with locations cannot go); the user last (refresh_tokens cascade).
        // A unit test cannot see the FK reasons, so this ordering assertion is the cheap guard
        // that makes any future reorder fail the build without Docker.
        InOrder order = inOrder(spaceService, itemService, locationService, userRepository);
        order.verify(spaceService).lockAllSpacesOfUser(userId);
        order.verify(itemService).deleteAllForUser(userId);
        order.verify(locationService).deleteAllForUser(userId);
        order.verify(spaceService).deleteAllForUser(userId);
        order.verify(userRepository).delete(user);
    }

    @Test
    void vanishedUserIs401WithoutAnNpeOrAnyServiceInteraction() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteOwnAccount(userId, "password123"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
        verifyNoInteractions(spaceService, locationService, itemService);
        verify(userRepository, never()).delete(any());
    }
}
