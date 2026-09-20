package az.technest.whereis.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import az.technest.whereis.assistant.AssistantMessageService;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
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
    @Mock
    private AssistantMessageService assistantMessageService;
    @Mock
    private az.technest.whereis.plan.SubscriptionCancellationService subscriptionCancellations;
    @Mock
    private az.technest.whereis.plan.rtdn.PlayNotificationPurgeService playNotifications;
    private AccountDeletionService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Real verifier, not a mock: the test must prove the service refuses on a genuine BCrypt mismatch.
        service = new AccountDeletionService(userRepository, new PasswordVerifier(encoder),
                spaceService, locationService, itemService, assistantMessageService,
                subscriptionCancellations, playNotifications);
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
        verifyNoInteractions(spaceService, locationService, itemService, assistantMessageService,
                subscriptionCancellations, playNotifications);
        verify(userRepository, never()).delete(any());
    }

    @Test
    void happyPathRunsTheCascadeInTheOnlyOrderTheForeignKeysAllow(CapturedOutput output) {
        User user = user();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(spaceService.lockAllSpacesOfUser(userId)).thenReturn(List.of(UUID.randomUUID()));
        when(assistantMessageService.deleteAllForUser(userId)).thenReturn(7);
        when(itemService.deleteAllForUser(userId)).thenReturn(new ItemDeletionSummary(3, 4));
        when(locationService.deleteAllForUser(userId)).thenReturn(5);
        when(spaceService.deleteAllForUser(userId)).thenReturn(1);
        when(playNotifications.purgeForUser(userId)).thenReturn(2);

        service.deleteOwnAccount(userId, "password123");

        // Locks first (no writer can interleave); assistant messages before items (so their
        // item_id/space_id SET NULL triggers never fire in the bulk deletes and the count is exact);
        // items before locations (RESTRICT); locations before spaces (a space with locations
        // cannot go); the user last (refresh_tokens cascade). A unit test cannot see the FK
        // reasons, so this ordering assertion is the cheap guard that makes any future reorder
        // fail the build without Docker.
        InOrder order = inOrder(spaceService, subscriptionCancellations, playNotifications,
                assistantMessageService, itemService, locationService, userRepository);
        order.verify(spaceService).lockAllSpacesOfUser(userId);
        // FIRST of the writes: stop the money before dismantling the account, and before the rows
        // it reads cascade away with the users row. Google Play does NOT cancel a subscription when
        // a user deletes their app account.
        order.verify(subscriptionCancellations).enqueueFor(userId);
        // SECOND, and it has the same deadline for the same reason: play_notifications has no
        // user_id, so the only thing that links a notification to this account is its purchase
        // token, and that join runs through user_subscriptions — which the users cascade takes
        // away at the end. Move this step after userRepository.delete and it silently purges
        // nothing, leaving Google's tokens and raw payloads behind a page that says otherwise.
        order.verify(playNotifications).purgeForUser(userId);
        order.verify(assistantMessageService).deleteAllForUser(userId);
        order.verify(itemService).deleteAllForUser(userId);
        order.verify(locationService).deleteAllForUser(userId);
        order.verify(spaceService).deleteAllForUser(userId);
        order.verify(userRepository).delete(user);
        // The summary line counts them from the returned value, never from a second query.
        assertThat(output.getOut()).contains("7 assistant messages").contains("3 items").contains("5 locations")
                .contains("subscription cancellations enqueued")
                .contains("2 billing notifications purged");
    }

    @Test
    void vanishedUserIs401WithoutAnNpeOrAnyServiceInteraction() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteOwnAccount(userId, "password123"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INVALID_CREDENTIALS));
        verifyNoInteractions(spaceService, locationService, itemService, assistantMessageService,
                subscriptionCancellations, playNotifications);
        verify(userRepository, never()).delete(any());
    }
}
