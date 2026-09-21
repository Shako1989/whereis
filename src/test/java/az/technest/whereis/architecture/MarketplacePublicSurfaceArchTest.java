package az.technest.whereis.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The marketplace board is the first surface in this application readable without a JWT, and
 * business rule 2 — "users can only ever see or touch their own data" — now has an explicit
 * exception carved out of it. These rules are what keep that exception the size it was designed to
 * be, as a BUILD FAILURE rather than a habit or a review comment.
 *
 * <p>One convention is not enough for the rule the whole product rests on, so there are three
 * independent barriers: the board's SQL has one table in its {@code FROM} clause (pinned by
 * {@code ListingSchemaTest} and by the DAO itself), these rules, and an integration test that walks
 * the whole public JSON tree looking for owner-only field names.
 */
@AnalyzeClasses(packages = "az.technest.whereis")
class MarketplacePublicSurfaceArchTest {

    /**
     * THE BRIEF'S HARDEST REQUIREMENT, AS A BUILD FAILURE. A listing must never be able to name,
     * resolve or render the item's internal location path — {@code ItemResponse} carries
     * {@code locationPath} and {@code LocationTreeDao} is what computes it, so depending on either
     * is the realistic route to publishing "Home &gt; Bedroom &gt; Wardrobe" to the open internet.
     */
    @ArchTest
    static final ArchRule theMarketplaceCannotSeeTheLocationTree =
            noClasses()
                    .that().resideInAPackage("..whereis.marketplace..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..whereis.location..", "..whereis.item.dto..", "..whereis.search..")
                    .because("the item's internal location path must never reach an anonymous "
                            + "visitor, and these are the three packages that could carry it");

    /**
     * Requests to the board arrive with NO principal, by construction: its security chain has no
     * {@code oauth2ResourceServer}, so even a valid token is ignored. {@code CurrentUser.id()}
     * there would throw — a 500 on a public endpoint — and this makes that a compile-time fact
     * rather than a runtime discovery.
     */
    @ArchTest
    static final ArchRule theBoardNeverAsksWhoIsCalling =
            noClasses()
                    .that().resideInAPackage("..whereis.marketplace.board..")
                    .should().dependOnClassesThat().haveSimpleName("CurrentUser")
                    .because("the board's chain has no JWT decoder, so there is never a principal "
                            + "to read; an endpoint that needs one belongs at a different path");

    /**
     * The board reads its own projection through its own DAO. Reaching into the seller's service
     * or its entity would give it a path to fields the public DTOs deliberately do not carry, and
     * a lazily-loaded entity is exactly how a field reappears in a response nobody intended.
     */
    @ArchTest
    static final ArchRule theBoardDoesNotReachIntoTheSellersSide =
            noClasses()
                    .that().resideInAPackage("..whereis.marketplace.board..")
                    .should().dependOnClassesThat().haveSimpleName("ListingService")
                    .because("the board has its own DAO and its own projection; going through the "
                            + "seller's service would hand it fields the public DTOs omit");

    /** Listings are owned rows, so the plan package remains the only reader of the subscription side. */
    @ArchTest
    static final ArchRule nothingOutsideTheMarketplaceAndThePlanReadsListings =
            noClasses()
                    .that().resideOutsideOfPackages("..whereis.marketplace..", "..whereis.plan..",
                            "..whereis.item..", "..whereis.user..")
                    .should().dependOnClassesThat().haveSimpleName("ListingRepository")
                    .because("the cap is counted in PlanLimitEnforcer, the archive guard is in "
                            + "ItemService, deletion is in AccountDeletionService, and listings are "
                            + "otherwise read only by marketplace/");
}
