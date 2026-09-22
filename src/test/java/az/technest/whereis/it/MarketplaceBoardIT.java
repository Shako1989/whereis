package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.auth.dto.LoginRequest;
import az.technest.whereis.auth.dto.RegisterRequest;
import az.technest.whereis.auth.dto.TokenPairResponse;
import az.technest.whereis.item.dto.CreateItemRequest;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.marketplace.ListingReportReason;
import az.technest.whereis.marketplace.ListingStatus;
import az.technest.whereis.marketplace.SellerBlockReason;
import az.technest.whereis.marketplace.board.dto.CreateReportRequest;
import az.technest.whereis.marketplace.moderation.SellerModerationController;
import az.technest.whereis.marketplace.moderation.dto.BlockSellerRequest;
import az.technest.whereis.marketplace.seller.dto.ListingRequest;
import az.technest.whereis.marketplace.seller.dto.MyListingResponse;
import az.technest.whereis.plan.Plan;
import az.technest.whereis.space.SpaceType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * The first surface in this application readable without a JWT. This IT protects the two things
 * that make that safe: the chain it is served by, and the shape of what it returns.
 */
class MarketplaceBoardIT extends AbstractIntegrationTest {

    private static final String BOARD = "/api/v1/market/listings";

    /**
     * Read off the controller's own constant rather than retyped, so a move of the mapping cannot
     * leave this suite testing a path nothing serves — and so
     * {@code moderationIsOnTheAuthenticatedChainAndNotUnderTheAnonymousBoardPath} is asserting
     * something about the application rather than about this string.
     */
    private static final String MODERATION = SellerModerationController.PATH;

    /**
     * Field names that exist on the OWNER's side of this application and must never appear in a
     * public body, at any depth. Asserted by walking the whole JSON tree rather than field by
     * field, because a field-level assertion only checks the fields somebody thought of.
     */
    private static final Set<String> OWNER_ONLY = Set.of(
            "locationPath", "currentLocationId", "itemId", "userId", "sellerId", "email",
            "archived", "primaryFileId", "normalizedName", "objectKey",
            "coverFileId", "hiddenReason", "hiddenNote", "sellerBlocked", "blockedBy",
            "totalElements", "totalPages");

    @Autowired
    private JwtEncoder jwtEncoder;

    // ---------------------------------------------------------------- the chain

    @Test
    void anAnonymousVisitorBrowsesTheBoardWithNoAuthorizationHeaderAtAll() {
        ResponseEntity<JsonNode> anonymous = rest.getForEntity(BOARD, JsonNode.class);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(anonymous.getBody().has("listings")).isTrue();
    }

    /**
     * THE TEST THAT MUST FAIL IF ANYONE MOVES THE BOARD ONTO THE MAIN CHAIN. {@code permitAll}
     * governs authorization, not decoding: on a chain with {@code oauth2ResourceServer} the bearer
     * filter would reject this token before any matcher was consulted. The access TTL is 15
     * minutes and the Android client browses the board continuously, so this is the normal case
     * rather than an edge case — and a test written with a freshly minted token would never see it.
     */
    @Test
    void anExpiredAccessTokenStillReadsTheBoard() {
        ResponseEntity<JsonNode> response = withAuthorization(BOARD, "Bearer " + expiredToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aMalformedBearerValueStillReadsTheBoard() {
        assertThat(withAuthorization(BOARD, "Bearer not-a-jwt").getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void theSameExpiredTokenIsStillRefusedByTheAuthenticatedApi() {
        // The paired negative: the board is open BECAUSE of its own chain, not because the token
        // was somehow accepted.
        assertThat(withAuthorization("/api/v1/spaces", "Bearer " + expiredToken()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theMarketMatcherOpensNothingElseUnderTheApi() {
        // LegalPagesIT#theLegalMatcherOpensNothingUnderTheApi's twin.
        assertThat(rest.getForEntity("/api/v1/spaces", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/api/v1/items", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/api/v1/users/me/listings", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anUnmatchedWriteMethodUnderTheMarketPathIsRefused() {
        // anyRequest().denyAll() is load-bearing: the matcher covers a subtree that will grow, and
        // a route added without its own matcher must be refused rather than silently anonymous.
        ResponseEntity<String> put = rest.exchange(BOARD + "/" + UUID.randomUUID(), HttpMethod.PUT,
                new HttpEntity<>("{}", jsonHeaders()), String.class);

        assertThat(put.getStatusCode()).isIn(HttpStatus.FORBIDDEN, HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------- what the board returns

    @Test
    void aPublishedListingIsVisibleToAnyoneAndLeaksNothingOwnerOnly() {
        Published published = publish("Samsung telefon", "BAKU");

        ResponseEntity<JsonNode> detail =
                rest.getForEntity(BOARD + "/" + published.listingId(), JsonNode.class);

        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = detail.getBody();
        assertThat(body.get("title").asText()).isEqualTo("Samsung telefon");
        // The board serves the CODE. The label is the client's, rendered from its own string
        // resources — no localised name reaches the database or any response.
        assertThat(body.get("city").asText()).isEqualTo("BAKU");
        assertThat(body.get("contactPhone").asText()).isEqualTo("+994501234567");

        assertNoOwnerOnlyField(body);
        assertNoOwnerOnlyField(rest.getForEntity(BOARD, JsonNode.class).getBody());
    }

    @Test
    void theInternalLocationPathNeverAppearsAnywhereInAPublicBody() {
        // The whole point of the feature's hardest rule, asserted on the RAW body rather than on
        // named fields — a field-level assertion only checks the fields somebody thought of.
        publish("Termos", "GANJA");

        String board = rest.getForEntity(BOARD, String.class).getBody();

        assertThat(board)
                .doesNotContain("Yataq otagi")
                .doesNotContain("Skaf")
                .doesNotContain("Ev");
    }

    @Test
    void thePublicPhotoUrlCarriesNeitherTheSellerIdNorTheItemId() {
        // The private object key is u/{userId}/i/{itemId}/{fileId} and a presigned URL carries the
        // key in its PATH, so serving the private object would publish both UUIDs to every crawler
        // and defeat leaving sellerId out of the DTO. The opaque p/ copy is what prevents it.
        Published published = publish("Kitab", "SUMQAYIT");

        String imageUrl = rest.getForEntity(BOARD + "/" + published.listingId(), JsonNode.class)
                .getBody().get("imageUrl").asText();

        assertThat(imageUrl).isNotBlank().contains("/p/");
        assertThat(imageUrl)
                .doesNotContain(published.userId().toString())
                .doesNotContain(published.itemId().toString());
    }

    @Test
    void theBoardPageCarriesNoTotalCount() {
        publish("Stol", "BAKU");

        JsonNode page = rest.getForEntity(BOARD, JsonNode.class).getBody();

        assertThat(page.has("totalElements")).isFalse();
        assertThat(page.has("totalPages")).isFalse();
        assertThat(page.has("hasMore")).isTrue();
    }

    @Test
    void theBoardResponseIsNotCacheable() {
        // The body embeds a presigned URL — a short-lived credential a proxy must not cache and
        // re-serve after it has expired.
        ResponseEntity<JsonNode> response = rest.getForEntity(BOARD, JsonNode.class);

        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    // ---------------------------------------------------------------- visibility

    @Test
    void aWithdrawnListingLeavesTheBoardAndItsDetailEndpoint() {
        Published published = publish("Velosiped", "BAKU");

        ResponseEntity<MyListingResponse> withdrawn = rest.exchange(
                "/api/v1/listings/" + published.listingId(), HttpMethod.DELETE,
                new HttpEntity<>(bearer(published.token())), MyListingResponse.class);
        assertThat(withdrawn.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(rest.getForEntity(BOARD + "/" + published.listingId(), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anOperatorHiddenListingIsIndistinguishableFromOneThatNeverExisted() {
        Published published = publish("Qazan", "BAKU");
        jdbc.update("update listings set hidden_at = now(), hidden_reason = 'PROHIBITED_ITEM' where id = ?",
                published.listingId());

        ResponseEntity<String> hidden =
                rest.getForEntity(BOARD + "/" + published.listingId(), String.class);
        ResponseEntity<String> neverExisted =
                rest.getForEntity(BOARD + "/" + UUID.randomUUID(), String.class);

        assertThat(hidden.getStatusCode()).isEqualTo(neverExisted.getStatusCode());
        assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aSecondUsersListingIsPublicOnTheBoardAndStill404OnTheOwnerApi() {
        // The new rule and business rule 2, in one test: publishing opens exactly one door.
        Published published = publish("Drel", "BAKU");
        String stranger = registerAndGetToken();

        assertThat(rest.getForEntity(BOARD + "/" + published.listingId(), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get(stranger, "/api/v1/listings/" + published.listingId(), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(stranger, "/api/v1/items/" + published.itemId(), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------- reports

    @Test
    void anAnonymousReportIsAcceptedAndSaysNothingAboutWhatHappened() {
        Published published = publish("Palto", "BAKU");

        ResponseEntity<String> real = rest.postForEntity(
                BOARD + "/" + published.listingId() + "/reports",
                new HttpEntity<>(new CreateReportRequest(ListingReportReason.SCAM_OR_FRAUD, "fake"),
                        jsonHeaders()), String.class);
        ResponseEntity<String> unknown = rest.postForEntity(
                BOARD + "/" + UUID.randomUUID() + "/reports",
                new HttpEntity<>(new CreateReportRequest(ListingReportReason.OTHER, null),
                        jsonHeaders()), String.class);

        // The SAME answer for both: an anonymous endpoint must have nothing to say to a prober.
        assertThat(real.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(jdbc.queryForObject("select count(*) from listing_reports where listing_id = ?",
                Integer.class, published.listingId())).isEqualTo(1);
    }

    @Test
    void nothingAboutTheReporterIsStored() {
        Published published = publish("Kreslo", "BAKU");
        rest.postForEntity(BOARD + "/" + published.listingId() + "/reports",
                new HttpEntity<>(new CreateReportRequest(ListingReportReason.OTHER, "note"),
                        jsonHeaders()), String.class);

        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'listing_reports'",
                String.class);

        assertThat(columns).doesNotContain("reporter_ip", "reporter_hash", "reporter_id", "ip_hash");
    }

    // ---------------------------------------------------------------- seller-level moderation

    /**
     * <strong>THE TEST THIS WHOLE CHANGE EXISTS FOR.</strong> One operation removes EVERY listing an
     * account has from the public board — the thing V12's per-listing kill-switch could not do, and
     * the thing Google Play's user-generated-content policy asks for — while touching nothing the
     * person owns privately.
     *
     * <p>The second half is asserted as strictly as the first, by comparing full row snapshots
     * rather than counts: a block that quietly archived an item, moved it, or dropped a photo would
     * be an account sanction masquerading as a marketplace one, and the seller would discover it in
     * their own inventory.
     */
    @Test
    void blockingASellerTakesEveryListingOffTheBoardAndLeavesTheirInventoryUntouched() {
        String tag = uniqueTag();
        Published seller = publish(tag + " telefon", "BAKU");
        grantTier(seller.userId(), Plan.STANDARD);       // FREE allows ONE active listing
        UUID second = publishAnother(seller, tag + " noutbuk", "BAKU");

        assertThat(boardIdsMatching(tag)).containsExactlyInAnyOrder(seller.listingId(), second);
        List<Map<String, Object>> itemsBefore = inventoryOf(seller.userId());
        List<Map<String, Object>> filesBefore = filesOf(seller.userId());

        block(seller.userId(), SellerBlockReason.SCAM_OR_FRAUD, "three upheld reports");

        // Gone from the board, and from its detail endpoint, with the same 404 as never-existed.
        assertThat(boardIdsMatching(tag)).isEmpty();
        assertThat(rest.getForEntity(BOARD + "/" + seller.listingId(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.getForEntity(BOARD + "/" + second, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        // ...and NOTHING of theirs changed. Not the items, not the photos, not the tree.
        assertThat(inventoryOf(seller.userId())).isEqualTo(itemsBefore);
        assertThat(filesOf(seller.userId())).isEqualTo(filesBefore);
        assertThat(jdbc.queryForObject("select count(*) from spaces where user_id = ?",
                Integer.class, seller.userId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from locations l join spaces s on s.id = l.space_id where s.user_id = ?",
                Integer.class, seller.userId())).isEqualTo(2);
        // And they can still READ their own inventory through the API they always used.
        assertThat(get(seller.token(), "/api/v1/items/" + seller.itemId(), ItemResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * The rows are FILTERED, never mutated — which is what makes unblocking a real reverse rather
     * than a second guess at what the listings looked like before.
     */
    @Test
    void aBlockedSellersRowsAreUntouchedSoUnblockingRestoresTheBoardExactly() {
        String tag = uniqueTag();
        Published seller = publish(tag + " velosiped", "GANJA");

        block(seller.userId(), SellerBlockReason.PROHIBITED_ITEMS, null);

        // Off the board, yet the row still says ACTIVE and not hidden. A block that had stamped
        // hidden_at here would be indistinguishable from a per-listing hide, and unblocking would
        // then have to guess which hides to lift.
        Map<String, Object> row = jdbc.queryForMap(
                "select status, hidden_at, hidden_reason, ended_at from listings where id = ?",
                seller.listingId());
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("hidden_at")).isNull();
        assertThat(row.get("hidden_reason")).isNull();
        assertThat(row.get("ended_at")).isNull();
        assertThat(boardIdsMatching(tag)).isEmpty();

        unblock(seller.userId());

        assertThat(boardIdsMatching(tag)).containsExactly(seller.listingId());
        assertThat(rest.getForEntity(BOARD + "/" + seller.listingId(), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject("select count(*) from blocked_sellers where user_id = ?",
                Integer.class, seller.userId())).isZero();
    }

    /** The eleventh listing, refused. A per-listing switch never could refuse it. */
    @Test
    void aBlockedSellerCannotPublishAnotherListingAndIsToldWhy() {
        Published seller = publish(uniqueTag() + " qazan", "BAKU");
        grantTier(seller.userId(), Plan.STANDARD);
        UUID freshItem = createItemWithPhoto(seller, "Ikinci esya");

        block(seller.userId(), SellerBlockReason.SPAM_OR_BULK_LISTINGS, null);

        ResponseEntity<JsonNode> refused = post(seller.token(),
                "/api/v1/items/" + freshItem + "/listing", listingRequest("Ikinci esya", "BAKU"),
                JsonNode.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("MARKETPLACE_BLOCKED");
        // The message names the reason and says the private data is safe — a sanction nobody
        // explains cannot be complied with.
        assertThat(refused.getBody().get("message").asText())
                .contains("spam or bulk listings")
                .contains("untouched");
        assertThat(jdbc.queryForObject("select count(*) from listings where item_id = ?",
                Integer.class, freshItem)).isZero();
    }

    /**
     * The seller's own screen must not show a healthy ACTIVE listing that no visitor can see. That
     * is the "vanished without explanation" failure one level up, and {@code sellerBlocked} is the
     * field that prevents it.
     */
    @Test
    void aBlockedSellerSeesOnTheirOwnListingThatTheAccountIsTheReason() {
        Published seller = publish(uniqueTag() + " palto", "BAKU");

        block(seller.userId(), SellerBlockReason.REPEATED_VIOLATIONS, "internal note, never on a wire");

        JsonNode mine = get(seller.token(), "/api/v1/listings/" + seller.listingId(), JsonNode.class)
                .getBody();

        assertThat(mine.get("sellerBlocked").asBoolean()).isTrue();
        // The row's own facts are untouched, and the two are independent: this is NOT a hide.
        assertThat(mine.get("hidden").asBoolean()).isFalse();
        assertThat(mine.get("hiddenReason").isNull()).isTrue();
        assertThat(mine.get("status").asText()).isEqualTo("ACTIVE");
        // The operator's note is for a colleague and reaches no response, ever.
        assertThat(get(seller.token(), "/api/v1/users/me/listings", String.class).getBody())
                .doesNotContain("internal note");
    }

    /** A marketplace sanction must not reach into the one control the person still legitimately has. */
    @Test
    void aBlockedSellerMayStillWithdrawTheirOwnListing() {
        Published seller = publish(uniqueTag() + " kreslo", "BAKU");
        block(seller.userId(), SellerBlockReason.OTHER, null);

        ResponseEntity<MyListingResponse> withdrawn = rest.exchange(
                "/api/v1/listings/" + seller.listingId(), HttpMethod.DELETE,
                new HttpEntity<>(bearer(seller.token())), MyListingResponse.class);

        assertThat(withdrawn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(withdrawn.getBody().status()).isEqualTo(ListingStatus.WITHDRAWN);
        assertThat(withdrawn.getBody().sellerBlocked()).isTrue();
    }

    /** Editing is refused, like a hide — but with the code that says the ACCOUNT is the problem. */
    @Test
    void aBlockedSellerCannotEditAListingEither() {
        Published seller = publish(uniqueTag() + " stol", "BAKU");
        block(seller.userId(), SellerBlockReason.OFFENSIVE_CONTENT, null);

        ResponseEntity<JsonNode> edited = rest.exchange("/api/v1/listings/" + seller.listingId(),
                HttpMethod.PUT,
                new HttpEntity<>(listingRequest("Yeni ad", "BAKU"), bearer(seller.token())),
                JsonNode.class);

        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(edited.getBody().get("code").asText()).isEqualTo("MARKETPLACE_BLOCKED");
    }

    @Test
    void theBlockIsOneRowRecordingWhoWhenAndWhy() {
        Published seller = publish(uniqueTag() + " termos", "SUMQAYIT");

        block(seller.userId(), SellerBlockReason.SCAM_OR_FRAUD, "reported by three buyers");
        // A second decision about the same account REPLACES the first rather than adding a row the
        // board would then have to disambiguate.
        block(seller.userId(), SellerBlockReason.OFFENSIVE_CONTENT, "and then this");

        Map<String, Object> row = jdbc.queryForMap(
                "select reason, note, blocked_by, blocked_at from blocked_sellers where user_id = ?",
                seller.userId());
        assertThat(jdbc.queryForObject("select count(*) from blocked_sellers where user_id = ?",
                Integer.class, seller.userId())).isEqualTo(1);
        assertThat(row.get("reason")).isEqualTo("OFFENSIVE_CONTENT");
        assertThat(row.get("note")).isEqualTo("and then this");
        assertThat(row.get("blocked_by")).isEqualTo(MODERATOR_EMAIL);
        assertThat(row.get("blocked_at")).isNotNull();
    }

    /**
     * Otherwise the operator's queue re-surfaces forever exactly the complaints they acted on, and
     * a queue query is useless after the first incident.
     */
    @Test
    void blockingClosesEveryOpenReportAgainstThatSellersListings() {
        Published seller = publish(uniqueTag() + " drel", "BAKU");
        rest.postForEntity(BOARD + "/" + seller.listingId() + "/reports",
                new HttpEntity<>(new CreateReportRequest(ListingReportReason.SCAM_OR_FRAUD, "fake"),
                        jsonHeaders()), String.class);

        block(seller.userId(), SellerBlockReason.SCAM_OR_FRAUD, null);

        Map<String, Object> report = jdbc.queryForMap(
                "select reviewed_at, review_outcome from listing_reports where listing_id = ?",
                seller.listingId());
        assertThat(report.get("reviewed_at")).isNotNull();
        assertThat(report.get("review_outcome")).isEqualTo("UPHELD");
    }

    // ---------------------------------------------------------------- who may moderate

    /**
     * <strong>The allowlist fails CLOSED, and this is the test that says so from outside.</strong>
     * Every account in every other IT is automatically not a moderator, so a rule that resolved an
     * unconfigured or non-matching allowlist to "everybody" would make this endpoint a self-service
     * way for any registered user to bar any other from the marketplace.
     */
    @Test
    void anOrdinaryAccountCannotBlockAnybody() {
        Published seller = publish(uniqueTag() + " kitab", "BAKU");
        String stranger = registerAndGetToken();

        ResponseEntity<JsonNode> refused = post(stranger,
                MODERATION + "/sellers/" + seller.userId() + "/block",
                new BlockSellerRequest(SellerBlockReason.OTHER, null), JsonNode.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("NOT_A_MODERATOR");
        assertThat(jdbc.queryForObject("select count(*) from blocked_sellers where user_id = ?",
                Integer.class, seller.userId())).isZero();
        // And the listing is still public, because nothing was written.
        assertThat(rest.getForEntity(BOARD + "/" + seller.listingId(), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * THE TEST THAT MUST FAIL IF ANYONE MOVES MODERATION UNDER {@code /api/v1/market/}. That path
     * is served by a chain with NO JWT decoder and {@code anyRequest().denyAll()}: a moderation
     * route there would answer 403 to its own moderator, and {@code CurrentUser.id()} would throw
     * {@code IllegalStateException} — a 500 with an ERROR log — for anyone it did reach.
     */
    @Test
    void moderationIsOnTheAuthenticatedChainAndNotUnderTheAnonymousBoardPath() {
        assertThat(MODERATION).doesNotStartWith("/api/v1/market");

        ResponseEntity<String> anonymous = rest.exchange(
                MODERATION + "/sellers/" + UUID.randomUUID() + "/block", HttpMethod.POST,
                new HttpEntity<>("{\"reason\":\"OTHER\"}", jsonHeaders()), String.class);

        // 401 from the resource server, not 403 from a denyAll matcher and not 500 from CurrentUser.
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** The operator pastes this id out of a SQL result, so a wrong one is the expected failure. */
    @Test
    void blockingAnAccountThatDoesNotExistIsA404() {
        ResponseEntity<JsonNode> missing = post(moderatorToken(),
                MODERATION + "/sellers/" + UUID.randomUUID() + "/block",
                new BlockSellerRequest(SellerBlockReason.OTHER, null), JsonNode.class);

        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody().get("code").asText()).isEqualTo("USER_NOT_FOUND");
    }

    /**
     * A sanction must never be able to refuse the deletion Play mandates: {@code blocked_sellers}
     * cascades with the account rather than holding a reference to it.
     */
    @Test
    void aBlockedSellerCanStillDeleteTheirAccountAndTheBlockGoesWithIt() {
        Published seller = publish(uniqueTag() + " sumka", "BAKU");
        block(seller.userId(), SellerBlockReason.SCAM_OR_FRAUD, null);

        ResponseEntity<String> deleted = deleteWithBody(seller.token(), "/api/v1/users/me",
                Map.of("password", PASSWORD), String.class);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("select count(*) from blocked_sellers where user_id = ?",
                Integer.class, seller.userId())).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private record Published(String token, UUID userId, UUID itemId, UUID listingId, UUID locationId) {
    }

    private Published publish(String title, String city) {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        UUID spaceId = createSpace(token, "Ev", SpaceType.HOME).id();
        UUID roomId = createLocation(token, spaceId, "Yataq otagi", LocationType.ROOM, null).id();
        UUID shelfId = createLocation(token, spaceId, "Skaf", LocationType.FURNITURE, roomId).id();
        UUID itemId = post(token, "/api/v1/items",
                new CreateItemRequest(title, null, null, shelfId), ItemResponse.class).getBody().id();
        uploadJpeg(token, itemId, true);

        ResponseEntity<MyListingResponse> created = post(token, "/api/v1/items/" + itemId + "/listing",
                listingRequest(title, city), MyListingResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new Published(token, userId, itemId, created.getBody().id(), shelfId);
    }

    /** A second listing for the SAME seller — what makes "one operation, every listing" testable. */
    private UUID publishAnother(Published seller, String title, String city) {
        UUID itemId = createItemWithPhoto(seller, title);
        ResponseEntity<MyListingResponse> created = post(seller.token(),
                "/api/v1/items/" + itemId + "/listing", listingRequest(title, city),
                MyListingResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().id();
    }

    private UUID createItemWithPhoto(Published seller, String name) {
        UUID itemId = post(seller.token(), "/api/v1/items",
                new CreateItemRequest(name, null, null, seller.locationId()), ItemResponse.class)
                .getBody().id();
        uploadJpeg(seller.token(), itemId, true);
        return itemId;
    }

    private static ListingRequest listingRequest(String title, String city) {
        return new ListingRequest(title, "Az islenmis, tam saz veziyyetde, qutusu ile birlikde.",
                new BigDecimal("250.00"), "+994 50 123 45 67", city, null);
    }

    /**
     * A per-test token in the listing title, so every board assertion filters to THIS test's rows.
     * The suite shares one database and one board, so "the board is empty" is never a safe
     * assertion — "no listing of mine is on it" is.
     */
    private static String uniqueTag() {
        return "zz" + UUID.randomUUID().toString().substring(0, 8).replace("-", "");
    }

    private List<UUID> boardIdsMatching(String tag) {
        JsonNode page = rest.getForEntity(BOARD + "?q=" + tag, JsonNode.class).getBody();
        List<UUID> ids = new ArrayList<>();
        page.get("listings").forEach(listing -> ids.add(UUID.fromString(listing.get("id").asText())));
        return ids;
    }

    private void block(UUID sellerId, SellerBlockReason reason, String note) {
        ResponseEntity<String> blocked = post(moderatorToken(),
                MODERATION + "/sellers/" + sellerId + "/block",
                new BlockSellerRequest(reason, note), String.class);
        assertThat(blocked.getStatusCode()).as("block %s", sellerId).isEqualTo(HttpStatus.NO_CONTENT);
    }

    private void unblock(UUID sellerId) {
        ResponseEntity<String> lifted = rest.exchange(
                MODERATION + "/sellers/" + sellerId + "/block", HttpMethod.DELETE,
                new HttpEntity<>(bearer(moderatorToken())), String.class);
        assertThat(lifted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /**
     * The one account on the allowlist. Registered on first use and logged into afterwards, because
     * the address is fixed configuration while the database outlives each test in this JVM.
     */
    private String moderatorToken() {
        if (moderatorToken == null) {
            ResponseEntity<TokenPairResponse> registered = rest.postForEntity("/api/v1/auth/register",
                    new RegisterRequest(MODERATOR_EMAIL, PASSWORD, "Market", "Moderator"),
                    TokenPairResponse.class);
            moderatorToken = registered.getStatusCode().is2xxSuccessful()
                    ? registered.getBody().accessToken()
                    : rest.postForEntity("/api/v1/auth/login",
                            new LoginRequest(MODERATOR_EMAIL, PASSWORD), TokenPairResponse.class)
                            .getBody().accessToken();
        }
        return moderatorToken;
    }

    private static String moderatorToken;

    /** Every item row of one account, ordered, for an "absolutely nothing changed" comparison. */
    private List<Map<String, Object>> inventoryOf(UUID userId) {
        return jdbc.queryForList("""
                select id, name, normalized_name, description, current_location_id, archived,
                       created_at, updated_at
                  from items where user_id = ? order by id
                """, userId);
    }

    private List<Map<String, Object>> filesOf(UUID userId) {
        return jdbc.queryForList("""
                select f.id, f.item_id, f.object_key, f.published_object_key, f.is_primary,
                       f.file_size, f.content_type
                  from item_files f join items i on i.id = f.item_id
                 where i.user_id = ? order by f.id
                """, userId);
    }

    private String expiredToken() {
        Instant issued = Instant.now().minus(2, ChronoUnit.HOURS);
        return jwtEncoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),
                        JwtClaimsSet.builder()
                                .issuer("whereis")
                                .subject(UUID.randomUUID().toString())
                                .issuedAt(issued)
                                .expiresAt(issued.plus(1, ChronoUnit.MINUTES))
                                .build()))
                .getTokenValue();
    }

    private ResponseEntity<JsonNode> withAuthorization(String path, String authorization) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, authorization);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static void assertNoOwnerOnlyField(JsonNode node) {
        List<String> found = new ArrayList<>();
        collectFieldNames(node, found);
        assertThat(found).as("owner-only fields in a public body").doesNotContainAnyElementsOf(OWNER_ONLY);
    }

    private static void collectFieldNames(JsonNode node, List<String> into) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                into.add(name);
                collectFieldNames(node.get(name), into);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectFieldNames(child, into));
        }
    }
}
