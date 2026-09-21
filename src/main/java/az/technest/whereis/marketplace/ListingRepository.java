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
 * Every finder here is userId-scoped, as §6 requires, EXCEPT {@link #findVisible(UUID)} — the
 * board's one read, which by definition has no user. That method is the reason
 * {@code ..marketplace.board..} exists as its own package with its own ArchUnit rules: it is the
 * only query in this application an unauthenticated stranger can reach.
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
     * The public detail read. The predicate IS {@link Listing#isPubliclyVisible()} expressed in
     * JPQL, and {@code ListingStatusTest} pins that the two agree: drift here does not fail a build
     * or a request, it publishes a withdrawn listing or hides every live one, silently.
     */
    @Query("select l from Listing l where l.id = :id and l.status = 'ACTIVE' and l.hiddenAt is null")
    Optional<Listing> findVisible(@Param("id") UUID id);

    /**
     * Account deletion, in one statement. NO {@code clearAutomatically}: the deletion transaction
     * still holds the managed {@code User} it loaded for the password check, and clearing would
     * detach it — {@code ItemRepository}'s comment verbatim, for the same reason.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from Listing l where l.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
