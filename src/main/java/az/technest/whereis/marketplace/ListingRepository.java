package az.technest.whereis.marketplace;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * <strong>Every finder here is userId-scoped</strong>, as §6 requires. Nothing on this interface is
 * reachable by an anonymous visitor: the board has its own package, its own DAO and its own
 * hand-written SQL, which is also why {@code ..marketplace.board..} carries its own ArchUnit rules.
 *
 * <p>A {@code findVisible} JPQL copy of the public visibility predicate used to live here and has
 * been REMOVED (V13). It had no callers — {@code MarketBoardDao} has served the public detail read
 * since V12 — and an unused second statement of the rule that decides what an anonymous stranger
 * may see is worse than no statement at all: V13 widened that rule with the seller-level block, and
 * a dormant copy admitting a blocked seller's listing was a trap waiting for its first caller.
 */
public interface ListingRepository extends JpaRepository<Listing, UUID> {

    Optional<Listing> findByIdAndUserId(UUID id, UUID userId);

    Optional<Listing> findByItemIdAndUserIdAndStatus(UUID itemId, UUID userId, ListingStatus status);

    /** The per-tier cap's count. SOLD and WITHDRAWN free room, exactly as archiving does for items. */
    long countByUserIdAndStatus(UUID userId, ListingStatus status);

    boolean existsByItemIdAndStatus(UUID itemId, ListingStatus status);

    List<Listing> findAllByItemIdAndStatus(UUID itemId, ListingStatus status);

    Page<Listing> findAllByUserId(UUID userId, Pageable pageable);

    Page<Listing> findAllByUserIdAndStatus(UUID userId, ListingStatus status, Pageable pageable);

    /**
     * Account deletion, in one statement. NO {@code clearAutomatically}: the deletion transaction
     * still holds the managed {@code User} it loaded for the password check, and clearing would
     * detach it — {@code ItemRepository}'s comment verbatim, for the same reason.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from Listing l where l.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
