package az.technest.whereis.marketplace.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import az.technest.whereis.common.error.ApiException;
import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.marketplace.BlockedSeller;
import az.technest.whereis.marketplace.BlockedSellerRepository;
import az.technest.whereis.marketplace.ListingReportOutcome;
import az.technest.whereis.marketplace.ListingReportRepository;
import az.technest.whereis.marketplace.SellerBlockReason;
import az.technest.whereis.marketplace.moderation.dto.BlockSellerRequest;
import az.technest.whereis.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

/**
 * The operator's two actions, and above all the thing that must never regress: <strong>the
 * allowlist fails CLOSED.</strong> An unset allowlist that resolved to "everybody" would turn a
 * moderation endpoint into a self-service way for any registered account to bar any other account
 * from the marketplace, so there is a test for the empty case, the wrong-caller case and the
 * vanished-caller case rather than only for the happy path.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SellerBlockServiceTest {

    private static final UUID MODERATOR = UUID.randomUUID();
    private static final UUID SELLER = UUID.randomUUID();
    private static final String MODERATOR_EMAIL = "ops@technest.az";

    @Mock
    private BlockedSellerRepository blockedSellers;
    @Mock
    private ListingReportRepository reports;
    @Mock
    private UserRepository users;

    private SellerBlockService serviceWithAllowlist(List<String> allowlist) {
        return new SellerBlockService(blockedSellers, reports, users,
                new ModerationProperties(allowlist));
    }

    private static BlockSellerRequest request() {
        return new BlockSellerRequest(SellerBlockReason.SCAM_OR_FRAUD, "  three reports, all real ");
    }

    // ------------------------------------------------------------------ fail closed

    @Test
    void anUnconfiguredAllowlistMeansNobodyRatherThanEverybody() {
        SellerBlockService service = serviceWithAllowlist(List.of());

        assertThatThrownBy(() -> service.block(MODERATOR, SELLER, request()))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> {
                    ApiException api = (ApiException) thrown;
                    assertThat(api.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(api.code()).isEqualTo(ErrorCode.NOT_A_MODERATOR);
                });
        // And not one statement was run: the refusal is decided before anything is read.
        verifyNoInteractions(blockedSellers, reports, users);
    }

    @Test
    void aNullAllowlistBindsToNobodyAndNotToNull() {
        assertThat(new ModerationProperties(null).normalizedModeratorEmails()).isEmpty();
    }

    @Test
    void aCallerWhoIsNotOnTheAllowlistIsRefusedAndWritesNothing() {
        when(users.findEmailById(MODERATOR)).thenReturn(Optional.of("someone.else@example.com"));
        SellerBlockService service = serviceWithAllowlist(List.of(MODERATOR_EMAIL));

        assertThatThrownBy(() -> service.block(MODERATOR, SELLER, request()))
                .isInstanceOf(ApiException.class);

        verify(blockedSellers, never()).save(any());
        verify(reports, never()).closeOpenReportsAgainstSeller(any(), any(), any());
    }

    /** A JWT subject outlives its account by up to the access TTL. That caller is not a moderator. */
    @Test
    void aVanishedCallerIsRefused() {
        when(users.findEmailById(MODERATOR)).thenReturn(Optional.empty());
        SellerBlockService service = serviceWithAllowlist(List.of(MODERATOR_EMAIL));

        assertThatThrownBy(() -> service.unblock(MODERATOR, SELLER))
                .isInstanceOf(ApiException.class);

        verify(blockedSellers, never()).deleteByUserId(any());
    }

    /**
     * The allowlist is compared in the form {@code users.email} is STORED in — registration runs
     * every address through {@code Names.normalize}. A deployment whose environment variable is
     * capitalised must still match, or the configuration silently authorizes nobody and the only
     * symptom is a 403 for the person who set it.
     */
    @Test
    void theAllowlistIsComparedInTheFormRegistrationStoredTheEmailIn() {
        when(users.findEmailById(MODERATOR)).thenReturn(Optional.of("ops@technest.az"));
        when(users.findEmailById(SELLER)).thenReturn(Optional.of("seller@example.com"));
        SellerBlockService service = serviceWithAllowlist(List.of("  OPS@TechNest.AZ  "));

        service.block(MODERATOR, SELLER, request());

        verify(blockedSellers).save(any(BlockedSeller.class));
    }

    // ------------------------------------------------------------------ blocking

    @Test
    void blockingRecordsWhoWhenWhyAndTheOperatorsNote() {
        when(users.findEmailById(MODERATOR)).thenReturn(Optional.of(MODERATOR_EMAIL));
        when(users.findEmailById(SELLER)).thenReturn(Optional.of("seller@example.com"));
        SellerBlockService service = serviceWithAllowlist(List.of(MODERATOR_EMAIL));

        Instant before = Instant.now();
        service.block(MODERATOR, SELLER, request());

        ArgumentCaptor<BlockedSeller> saved = ArgumentCaptor.forClass(BlockedSeller.class);
        verify(blockedSellers).save(saved.capture());
        BlockedSeller block = saved.getValue();
        assertThat(block.getUserId()).isEqualTo(SELLER);
        assertThat(block.getReason()).isEqualTo(SellerBlockReason.SCAM_OR_FRAUD);
        assertThat(block.getBlockedBy()).isEqualTo(MODERATOR_EMAIL);
        assertThat(block.getBlockedAt()).isAfterOrEqualTo(before);
        // Cleaned, like every other stored free-text field in this application.
        assertThat(block.getNote()).isEqualTo("three reports, all real");
    }

    /**
     * Without this the operator's queue re-surfaces forever exactly the complaints they just acted
     * on, which is what makes {@code reviewed_at} / {@code review_outcome} exist at all.
     */
    @Test
    void blockingClosesEveryOpenReportAgainstThatSellersListings() {
        when(users.findEmailById(MODERATOR)).thenReturn(Optional.of(MODERATOR_EMAIL));
        when(users.findEmailById(SELLER)).thenReturn(Optional.of("seller@example.com"));
        when(reports.closeOpenReportsAgainstSeller(eq(SELLER), any(), any())).thenReturn(4);
        SellerBlockService service = serviceWithAllowlist(List.of(MODERATOR_EMAIL));

        service.block(MODERATOR, SELLER, request());

        verify(reports).closeOpenReportsAgainstSeller(eq(SELLER),
                eq(ListingReportOutcome.UPHELD), any(Instant.class));
    }

    /**
     * An operator pastes the seller id out of a SQL result, so a wrong one is the EXPECTED failure.
     * 404 rather than the foreign key's "the request conflicts with existing data".
     */
    @Test
    void blockingAnAccountThatDoesNotExistIs404AndWritesNothing() {
        when(users.findEmailById(MODERATOR)).thenReturn(Optional.of(MODERATOR_EMAIL));
        when(users.findEmailById(SELLER)).thenReturn(Optional.empty());
        SellerBlockService service = serviceWithAllowlist(List.of(MODERATOR_EMAIL));

        assertThatThrownBy(() -> service.block(MODERATOR, SELLER, request()))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertThat(((ApiException) thrown).status())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verify(blockedSellers, never()).save(any());
    }

    // ------------------------------------------------------------------ unblocking

    @Test
    void unblockingRemovesTheRow() {
        when(users.findEmailById(MODERATOR)).thenReturn(Optional.of(MODERATOR_EMAIL));
        when(blockedSellers.findByUserId(SELLER)).thenReturn(Optional.of(BlockedSeller.builder()
                .userId(SELLER)
                .blockedAt(Instant.now())
                .reason(SellerBlockReason.SPAM_OR_BULK_LISTINGS)
                .blockedBy(MODERATOR_EMAIL)
                .build()));
        SellerBlockService service = serviceWithAllowlist(List.of(MODERATOR_EMAIL));

        service.unblock(MODERATOR, SELLER);

        verify(blockedSellers).deleteByUserId(SELLER);
    }

    /** The operator's goal state is "not blocked", and it is already reached. */
    @Test
    void unblockingAnAccountThatWasNotBlockedIsANoOpRatherThanA404() {
        when(users.findEmailById(MODERATOR)).thenReturn(Optional.of(MODERATOR_EMAIL));
        when(blockedSellers.findByUserId(SELLER)).thenReturn(Optional.empty());
        SellerBlockService service = serviceWithAllowlist(List.of(MODERATOR_EMAIL));

        service.unblock(MODERATOR, SELLER);

        verify(blockedSellers, never()).deleteByUserId(any());
    }
}
