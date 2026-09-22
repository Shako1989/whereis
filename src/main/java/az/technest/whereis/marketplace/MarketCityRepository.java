package az.technest.whereis.marketplace;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The city reference table, read-only in this application: every row is seeded by V15 and there is
 * deliberately no writer — adding a place is a migration, retiring one is an operator UPDATE of
 * {@code active}.
 *
 * <p>Not userId-scoped, and it does not need to be: nothing here belongs to anybody. The §6 rule is
 * about OWNED aggregates, and this is public reference data an anonymous visitor is served in full.
 * {@code findById} is still avoided — the marketplace package is inside
 * {@code OwnershipScopingArchTest}'s list, so calling it fails the build — and the derived names
 * below say which question is being asked, which reads better anyway.
 */
public interface MarketCityRepository extends JpaRepository<MarketCity, String> {

    /**
     * The picker: every place a NEW listing may name, in the order the picker shows them. The order
     * comes from the column, so no caller sorts and no caller needs an Azerbaijani collator.
     */
    List<MarketCity> findAllByActiveTrueOrderBySortOrderAsc();

    /**
     * The publish-time gate. {@code active} is part of the question on purpose: a retired place
     * must not accept NEW listings even though existing ones keep it, and a client holding a
     * long-cached picker is exactly how a retired code arrives.
     */
    boolean existsByCodeAndActiveTrue(String code);
}
