package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.item.dto.CreateItemRequest;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.marketplace.ListingReportReason;
import az.technest.whereis.marketplace.board.dto.CreateReportRequest;
import az.technest.whereis.marketplace.seller.dto.ListingRequest;
import az.technest.whereis.marketplace.seller.dto.MyListingResponse;
import az.technest.whereis.space.SpaceType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
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
     * Field names that exist on the OWNER's side of this application and must never appear in a
     * public body, at any depth. Asserted by walking the whole JSON tree rather than field by
     * field, because a field-level assertion only checks the fields somebody thought of.
     */
    private static final Set<String> OWNER_ONLY = Set.of(
            "locationPath", "currentLocationId", "itemId", "userId", "sellerId", "email",
            "archived", "primaryFileId", "normalizedName", "normalizedCity", "objectKey",
            "coverFileId", "hiddenReason", "hiddenNote", "totalElements", "totalPages");

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
        Published published = publish("Samsung telefon", "Baki");

        ResponseEntity<JsonNode> detail =
                rest.getForEntity(BOARD + "/" + published.listingId(), JsonNode.class);

        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = detail.getBody();
        assertThat(body.get("title").asText()).isEqualTo("Samsung telefon");
        assertThat(body.get("city").asText()).isEqualTo("Baki");
        assertThat(body.get("contactPhone").asText()).isEqualTo("+994501234567");

        assertNoOwnerOnlyField(body);
        assertNoOwnerOnlyField(rest.getForEntity(BOARD, JsonNode.class).getBody());
    }

    @Test
    void theInternalLocationPathNeverAppearsAnywhereInAPublicBody() {
        // The whole point of the feature's hardest rule, asserted on the RAW body rather than on
        // named fields — a field-level assertion only checks the fields somebody thought of.
        publish("Termos", "Gence");

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
        Published published = publish("Kitab", "Sumqayit");

        String imageUrl = rest.getForEntity(BOARD + "/" + published.listingId(), JsonNode.class)
                .getBody().get("imageUrl").asText();

        assertThat(imageUrl).isNotBlank().contains("/p/");
        assertThat(imageUrl)
                .doesNotContain(published.userId().toString())
                .doesNotContain(published.itemId().toString());
    }

    @Test
    void theBoardPageCarriesNoTotalCount() {
        publish("Stol", "Baki");

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
        Published published = publish("Velosiped", "Baki");

        ResponseEntity<MyListingResponse> withdrawn = rest.exchange(
                "/api/v1/listings/" + published.listingId(), HttpMethod.DELETE,
                new HttpEntity<>(bearer(published.token())), MyListingResponse.class);
        assertThat(withdrawn.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(rest.getForEntity(BOARD + "/" + published.listingId(), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anOperatorHiddenListingIsIndistinguishableFromOneThatNeverExisted() {
        Published published = publish("Qazan", "Baki");
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
        Published published = publish("Drel", "Baki");
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
        Published published = publish("Palto", "Baki");

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
        Published published = publish("Kreslo", "Baki");
        rest.postForEntity(BOARD + "/" + published.listingId() + "/reports",
                new HttpEntity<>(new CreateReportRequest(ListingReportReason.OTHER, "note"),
                        jsonHeaders()), String.class);

        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns where table_name = 'listing_reports'",
                String.class);

        assertThat(columns).doesNotContain("reporter_ip", "reporter_hash", "reporter_id", "ip_hash");
    }

    // ---------------------------------------------------------------- helpers

    private record Published(String token, UUID userId, UUID itemId, UUID listingId) {
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
                new ListingRequest(title, "Az islenmis, tam saz veziyyetde, qutusu ile birlikde.",
                        new BigDecimal("250.00"), "+994 50 123 45 67", city, null),
                MyListingResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new Published(token, userId, itemId, created.getBody().id());
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
