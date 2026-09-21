package az.technest.whereis.marketplace.moderation;

import az.technest.whereis.common.error.ErrorCode;
import az.technest.whereis.common.error.ForbiddenException;
import az.technest.whereis.common.error.NotFoundException;
import az.technest.whereis.common.util.Names;
import az.technest.whereis.marketplace.BlockedSeller;
import az.technest.whereis.marketplace.BlockedSellerRepository;
import az.technest.whereis.marketplace.ListingReportOutcome;
import az.technest.whereis.marketplace.ListingReportRepository;
import az.technest.whereis.marketplace.moderation.dto.BlockSellerRequest;
import az.technest.whereis.user.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Barring a seller from the public board, and lifting it again.
 *
 * <p><strong>Why this is an endpoint and not the SQL the runbook would otherwise prescribe.</strong>
 * V12 made the opposite call for the per-listing kill-switch — {@code deploy/README.md} Step 12b
 * still hides a listing with a hand-written {@code UPDATE} — and the argument there was that an
 * admin endpoint needs an admin auth model this application does not have. That argument does not
 * survive contact with a SELLER-level sanction, for two reasons. A hand-written {@code UPDATE}
 * records that somebody decided something and nothing about who or why, and "we blocked them"
 * with nothing behind it is not an answer to give Google, a seller, or a court. And the second: a
 * per-listing hide is one row an operator is already looking at, while a block changes what a whole
 * account may do — the class of action that must leave a record by construction rather than by the
 * diligence of whoever was at the keyboard. The auth model is
 * {@link ModerationProperties}: an e-mail allowlist that fails closed.
 *
 * <p><strong>Reading the queue is deliberately still SQL.</strong> A moderation UI is not worth
 * building before the board has traffic, and a read endpoint would need its own pagination, its own
 * DTOs and its own rules about showing one user's data to another. ACTING is what has to leave a
 * record; LOOKING is documented SQL in Step 12.
 *
 * <p>Nothing here calls MinIO. The published photo copies are left in place on purpose: a block is
 * REVERSIBLE, and deleting the artefact would mean an unblock restored a listing the board could no
 * longer serve a picture for. A presigned URL already handed out stays valid for the presign TTL
 * either way, and Step 12b's paste-ready {@code storage_deletion_queue} insert is the escape hatch
 * for the case where minutes matter.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerBlockService {

    private final BlockedSellerRepository blockedSellers;
    private final ListingReportRepository reports;
    private final UserRepository users;
    private final ModerationProperties properties;

    /**
     * Bars an account from the board. Every ACTIVE listing it owns stops being visible on the next
     * request — through the anti-join in {@code MarketBoardDao}'s visibility predicate, not by
     * mutating the rows — and every open report against any of its listings is closed as
     * {@code UPHELD}, so the operator's queue does not re-surface what they have just acted on.
     *
     * <p>Idempotent by the primary key: blocking a blocked seller replaces the reason, the note, the
     * author and the timestamp with the new decision's, rather than adding a second row.
     */
    @Transactional
    public void block(UUID moderatorId, UUID sellerId, BlockSellerRequest request) {
        String moderator = requireModerator(moderatorId);
        requireAccountExists(sellerId);

        Instant now = Instant.now();
        blockedSellers.save(BlockedSeller.builder()
                .userId(sellerId)
                .blockedAt(now)
                .reason(request.reason())
                .note(Names.clean(request.note()))
                .blockedBy(moderator)
                .build());
        int closed = reports.closeOpenReportsAgainstSeller(sellerId, ListingReportOutcome.UPHELD, now);

        // The reason is the operator's judgement of their own decision, not a user's message, so
        // §6's "never log user messages above DEBUG" does not reach it. The NOTE is still not
        // logged: it belongs in the row, where it survives log rotation.
        log.info("Moderator {} blocked seller {} ({}), closing {} open report(s)",
                moderator, sellerId, request.reason(), closed);
    }

    /**
     * Lifts a block, which puts every listing the account still has back on the board untouched —
     * the payoff of filtering rather than stamping {@code hidden_at} onto the seller's rows.
     *
     * <p>It exists because a sanction with no reverse is a trap: it is applied by a human reading a
     * queue, humans act on the wrong row, and the only other repair would be the hand-written SQL
     * this endpoint exists to replace. Unblocking an account that was not blocked is a no-op rather
     * than a 404 — the operator's goal state is "not blocked", and it is reached.
     *
     * <p>The row's four audit facts are logged BEFORE it is deleted. That log line is the only
     * surviving record of a lifted block, and V13 records why the alternative — an append-only
     * ledger of past sanctions — is a retention decision that belongs with the public pages rather
     * than in this method.
     */
    @Transactional
    public void unblock(UUID moderatorId, UUID sellerId) {
        String moderator = requireModerator(moderatorId);
        Optional<BlockedSeller> existing = blockedSellers.findByUserId(sellerId);
        if (existing.isEmpty()) {
            log.info("Moderator {} unblocked seller {}, which was not blocked", moderator, sellerId);
            return;
        }
        BlockedSeller block = existing.get();
        log.info("Moderator {} unblocked seller {}, lifting a {} block placed by {} at {}",
                moderator, sellerId, block.getReason(), block.getBlockedBy(), block.getBlockedAt());
        blockedSellers.deleteByUserId(sellerId);
    }

    /**
     * @return the caller's e-mail, which is what goes into the audit column
     * @throws ForbiddenException 403 {@code NOT_A_MODERATOR} — including when NO moderator is
     *                            configured at all, which is the fail-closed case and the one a
     *                            misread of this method would turn into "everybody"
     */
    private String requireModerator(UUID callerId) {
        Set<String> allowed = properties.normalizedModeratorEmails();
        if (allowed.isEmpty()) {
            log.warn("A moderation action was refused: whereis.marketplace.moderation"
                    + ".moderator-emails is not set, so nobody may moderate");
            throw notAModerator();
        }
        // The e-mail is read by id and compared in the form registration stored it. The caller id
        // comes from the JWT subject, so a vanished account is possible and is a refusal.
        String email = users.findEmailById(callerId).orElse(null);
        if (email == null || !allowed.contains(email)) {
            // The id, never the e-mail: a refused caller's address in a log is an identifier we
            // have no reason to keep. The id is enough to find them in the database.
            log.warn("User {} attempted a marketplace moderation action and is not a moderator", callerId);
            throw notAModerator();
        }
        return email;
    }

    /**
     * A wrong seller id is a 404 rather than the 409 the foreign key would otherwise produce. The
     * operator pastes this id out of a SQL result or a report row, so getting it wrong is the
     * expected failure and "the request conflicts with existing data" would be an unhelpful answer
     * to it.
     */
    private void requireAccountExists(UUID sellerId) {
        if (users.findEmailById(sellerId).isEmpty()) {
            throw new NotFoundException(ErrorCode.USER_NOT_FOUND, "Account not found");
        }
    }

    private static ForbiddenException notAModerator() {
        return new ForbiddenException(ErrorCode.NOT_A_MODERATOR,
                "This action is restricted to marketplace moderators");
    }
}
