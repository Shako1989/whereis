package az.technest.whereis.marketplace;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The block list. Two methods, one row each, both on the primary key.
 *
 * <p><strong>The public board does NOT read this repository.</strong> It excludes blocked sellers
 * inside its own SQL, as a {@code NOT EXISTS} anti-join in {@code MarketBoardDao}'s visibility
 * predicate — a per-row existence check from Java would be an N+1 on the hottest anonymous request
 * in the system, and the page would then have to be re-filtered after the {@code LIMIT} had already
 * been applied.
 *
 * <p>Not userId-scoped in the §6 sense, and it does not need to be: the primary key IS a user id
 * and nothing here belongs to the caller — the rows are an operator's notes ABOUT accounts. That is
 * also why {@link #findByUserId(UUID)} exists rather than {@code findById}: the marketplace package
 * is inside {@code OwnershipScopingArchTest}'s list, so a {@code findById} call there fails the
 * build, and the derived name says which id is meant.
 */
public interface BlockedSellerRepository extends JpaRepository<BlockedSeller, UUID> {

    /**
     * The whole row, not a boolean: every caller that needs to know THAT an account is blocked also
     * needs the reason (the seller is told why) or the author (the unblock log line names who
     * decided what is being reversed). One primary-key lookup either way.
     */
    Optional<BlockedSeller> findByUserId(UUID userId);

    /** Unblocking. Returns 0 when the account was not blocked, which is not an error. */
    long deleteByUserId(UUID userId);
}
