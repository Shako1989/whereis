package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import az.technest.whereis.assistant.dto.AssistantSearchRequest;
import az.technest.whereis.assistant.dto.AssistantSearchResponse;
import az.technest.whereis.assistant.dto.RememberRequest;
import az.technest.whereis.assistant.dto.RememberResponse;
import az.technest.whereis.auth.dto.RefreshRequest;
import az.technest.whereis.auth.dto.TokenPairResponse;
import az.technest.whereis.item.dto.CreateItemRequest;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.item.dto.MoveItemRequest;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.location.dto.LocationResponse;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.CreateSpaceRequest;
import az.technest.whereis.space.dto.SpaceResponse;
import az.technest.whereis.plan.play.FakePlaySubscriptionsApi;
import az.technest.whereis.storage.MinioAdapter;
import az.technest.whereis.storage.dto.PresignedUrlResponse;
import az.technest.whereis.user.dto.DeleteAccountRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code DELETE /api/v1/users/me}: password-gated, single-transaction hard delete of the caller's
 * account — outbox rows first, then assistant messages, items, locations, spaces, user — with the MinIO binaries
 * removed afterwards by the janitor. Row counts are asserted straight from the database because
 * the API can no longer see a deleted account.
 */
class AccountDeletionIT extends AbstractIntegrationTest {

    private static final String ME = "/api/v1/users/me";

    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private MinioAdapter minioAdapter;
    @Autowired
    private az.technest.whereis.plan.play.PlaySubscriptionsApi play;
    /** Driven by hand: the scheduled flag is off in the shared IT context so one pass is assertable. */
    @Autowired
    private az.technest.whereis.plan.reconcile.PlayCancellationJanitor janitor;
    /** Same reason. The RTDN ledger's retention sweep — the half account deletion cannot reach. */
    @Autowired
    private az.technest.whereis.plan.reconcile.PlayNotificationJanitor notificationJanitor;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** Everything a test needs to remember about an account in order to prove it is gone. */
    private record Account(TokenPairResponse tokens, UUID userId, List<UUID> spaceIds, List<UUID> itemIds,
                           List<String> objectKeys, String presignedUrl) {

        String token() {
            return tokens.accessToken();
        }

        String keyPrefix() {
            return "u/" + userId + "/%";
        }
    }

    private record Counts(int users, int refreshTokens, int spaces, int locations, int items, int history,
                          int files, int outboxRows, int assistantMessages) {
    }

    /** Bob's rows and API views, for the byte-identical comparison around Alice's deletion. */
    private record Snapshot(List<Map<String, Object>> spaces, List<Map<String, Object>> locations,
                            List<Map<String, Object>> items, List<Map<String, Object>> history,
                            List<Map<String, Object>> files, List<Map<String, Object>> assistantMessages,
                            JsonNode itemsPage, List<JsonNode> trees) {
    }

    // ---------------------------------------------------------------- fixtures

    private UUID createItem(String token, UUID locationId, String name) {
        ResponseEntity<ItemResponse> created = post(token, "/api/v1/items",
                new CreateItemRequest(name, null, null, locationId), ItemResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return created.getBody().id();
    }

    private RememberResponse remember(String token, String message, UUID spaceId) {
        ResponseEntity<RememberResponse> response = post(token, "/api/v1/assistant/remember",
                new RememberRequest(message, spaceId, null), RememberResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private void insertFailedAssistantRow(UUID userId) {
        // FAILED cannot be produced through the API with the mock provider; this is the exact shape
        // the openai/claude path writes (interpretation NULL, no item, no space).
        jdbc.update("""
                insert into assistant_messages (id, user_id, mode, message, outcome, provider, model, prompt_version)
                values (?, ?, 'REMEMBER', 'a sentence the provider never answered', 'FAILED', 'openai', 'test',
                        'sha256:000000000000')
                """, UUID.randomUUID(), userId);
    }

    private String presign(String token, UUID itemId, UUID fileId) {
        return get(token, "/api/v1/items/" + itemId + "/files/" + fileId + "/url", PresignedUrlResponse.class)
                .getBody().url();
    }

    private List<String> objectKeysOf(UUID userId) {
        return jdbc.queryForList("""
                select f.object_key from item_files f join items i on i.id = f.item_id
                where i.user_id = ? order by f.object_key
                """, String.class, userId);
    }

    /**
     * 2 spaces, a 4-level chain (Room > Wardrobe > Shelf > Box) plus a second-space root, 3 items,
     * 4 photos including one primary, one move so the history holds a closed and an open row, and one
     * assistant message (the zero-write NEEDS_CONFIRMATION, so no item or location count moves).
     */
    private Account buildRealisticAccount() {
        TokenPairResponse tokens = register();
        String token = tokens.accessToken();
        UUID userId = subjectOf(token);
        // Two spaces: beyond the free tier, so this is a granted account (see grantUnlimited).
        grantUnlimited(userId);
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        SpaceResponse office = createSpace(token, "Office", SpaceType.OFFICE);
        LocationResponse bedroom = createLocation(token, home.id(), "Bedroom", LocationType.ROOM, null);
        LocationResponse wardrobe = createLocation(token, home.id(), "Wardrobe", LocationType.FURNITURE, bedroom.id());
        LocationResponse shelf = createLocation(token, home.id(), "Shelf", LocationType.SHELF, wardrobe.id());
        LocationResponse box = createLocation(token, home.id(), "Box", LocationType.BOX, shelf.id());
        LocationResponse desk = createLocation(token, office.id(), "Desk", LocationType.DESK, null);
        UUID passport = createItem(token, box.id(), "Passport");
        UUID keys = createItem(token, shelf.id(), "Keys");
        UUID laptop = createItem(token, desk.id(), "Laptop");
        UUID cover = uploadJpeg(token, passport, true);
        uploadJpeg(token, passport, false);
        uploadJpeg(token, keys, false);
        uploadJpeg(token, laptop, false);
        ResponseEntity<ItemResponse> moved = post(token, "/api/v1/items/" + keys + "/move",
                new MoveItemRequest(desk.id(), "Took them to work"), ItemResponse.class);
        assertThat(moved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(remember(token, "I put my charger in the desk drawer", null).status())
                .isEqualTo(RememberResponse.Status.NEEDS_CONFIRMATION);
        return new Account(tokens, userId, List.of(home.id(), office.id()), List.of(passport, keys, laptop),
                objectKeysOf(userId), presign(token, passport, cover));
    }

    /** One {@code depth}-level chain per space; items spread over every chain node, one photo each. */
    private Account buildAccount(int spaceCount, int depth, int itemCount) {
        TokenPairResponse tokens = register();
        String token = tokens.accessToken();
        UUID userId = subjectOf(token);
        // Granted: callers ask for several spaces and dozens of items, which the free tier refuses.
        grantUnlimited(userId);
        List<UUID> spaceIds = new ArrayList<>();
        List<UUID> nodes = new ArrayList<>();
        for (int s = 0; s < spaceCount; s++) {
            SpaceResponse space = createSpace(token, "Space " + s, SpaceType.OTHER);
            spaceIds.add(space.id());
            UUID parent = null;
            for (int d = 0; d < depth; d++) {
                parent = createLocation(token, space.id(), "Level " + d, LocationType.CONTAINER, parent).id();
                nodes.add(parent);
            }
        }
        List<UUID> itemIds = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            UUID itemId = createItem(token, nodes.get(i % nodes.size()), "Item " + i);
            itemIds.add(itemId);
            uploadJpeg(token, itemId, i % 2 == 0);
        }
        return new Account(tokens, userId, List.copyOf(spaceIds), List.copyOf(itemIds), objectKeysOf(userId), null);
    }

    // ---------------------------------------------------------------- database probes

    private static String placeholders(Collection<?> values) {
        return String.join(",", Collections.nCopies(values.size(), "?"));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int countIn(String table, String column, List<UUID> ids) {
        return count("select count(*) from " + table + " where " + column + " in (" + placeholders(ids) + ")",
                ids.toArray());
    }

    private int outboxRowsFor(Account account) {
        return count("select count(*) from storage_deletion_queue where object_key like ?", account.keyPrefix());
    }

    private Counts countsOf(Account a) {
        return new Counts(
                count("select count(*) from users where id = ?", a.userId()),
                count("select count(*) from refresh_tokens where user_id = ?", a.userId()),
                count("select count(*) from spaces where user_id = ?", a.userId()),
                countIn("locations", "space_id", a.spaceIds()),
                count("select count(*) from items where user_id = ?", a.userId()),
                countIn("item_location_history", "item_id", a.itemIds()),
                countIn("item_files", "item_id", a.itemIds()),
                outboxRowsFor(a),
                count("select count(*) from assistant_messages where user_id = ?", a.userId()));
    }

    private Snapshot snapshot(Account a) {
        JsonNode page = get(a.token(), "/api/v1/items?size=50", JsonNode.class).getBody();
        // A presigned URL is re-signed with the current second, so it legitimately differs between
        // two calls; the stable file id stays and is compared.
        page.get("content").forEach(row -> ((ObjectNode) row).remove("primaryImageUrl"));
        List<JsonNode> trees = a.spaceIds().stream()
                .map(id -> get(a.token(), "/api/v1/spaces/" + id + "/location-tree", JsonNode.class).getBody())
                .toList();
        return new Snapshot(
                jdbc.queryForList("select * from spaces where user_id = ? order by id", a.userId()),
                jdbc.queryForList("select * from locations where space_id in (" + placeholders(a.spaceIds())
                        + ") order by id", a.spaceIds().toArray()),
                jdbc.queryForList("select * from items where user_id = ? order by id", a.userId()),
                jdbc.queryForList("select * from item_location_history where item_id in ("
                        + placeholders(a.itemIds()) + ") order by id", a.itemIds().toArray()),
                jdbc.queryForList("select * from item_files where item_id in (" + placeholders(a.itemIds())
                        + ") order by id", a.itemIds().toArray()),
                jdbc.queryForList("select * from assistant_messages where user_id = ? order by id", a.userId()),
                page, trees);
    }

    private HttpResponse<byte[]> fetch(String url) throws IOException, InterruptedException {
        return httpClient.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private ResponseEntity<Void> deleteAccount(Account account) {
        return deleteWithBody(account.token(), ME, new DeleteAccountRequest(PASSWORD), Void.class);
    }

    private Statistics statistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    /**
     * The janitor shares the database and sweeps every 2 s in the test profile. Assertions that
     * count outbox rows or Hibernate statements right after a delete would race it, so: wait for
     * the outbox to be fully drained, then for the next (now single-statement) sweep to happen.
     * That leaves ~2 s in which the janitor is guaranteed silent — far longer than one request.
     */
    private void awaitQuietJanitorWindow() {
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200))
                .until(() -> count("select count(*) from storage_deletion_queue") == 0);
        Statistics statistics = statistics();
        long before = statistics.getPrepareStatementCount();
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20))
                .until(() -> statistics.getPrepareStatementCount() > before);
    }

    // ---------------------------------------------------------------- tests

    @Test
    void everythingOfTheUserIsGoneAndOneOutboxRowPerFileRemains() {
        Account alice = buildRealisticAccount();
        assertThat(countsOf(alice)).isEqualTo(new Counts(1, 1, 2, 5, 3, 4, 4, 0, 1));
        awaitQuietJanitorWindow();

        ResponseEntity<Void> deleted = deleteAccount(alice);
        Counts after = countsOf(alice);
        List<String> queued = jdbc.queryForList(
                "select object_key from storage_deletion_queue where object_key like ? order by object_key",
                String.class, alice.keyPrefix());

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(deleted.getBody()).isNull();
        // Every row of hers is gone in one transaction; the only trace is exactly one outbox row
        // per photo, keyed by the object keys that item_files no longer holds.
        assertThat(after).isEqualTo(new Counts(0, 0, 0, 0, 0, 0, 0, 4, 0));
        assertThat(queued).containsExactlyElementsOf(alice.objectKeys());
    }

    @Test
    void anotherUsersDataIsByteIdenticalBeforeAndAfter() throws Exception {
        Account alice = buildRealisticAccount();
        Account bob = buildRealisticAccount();
        Snapshot before = snapshot(bob);
        assertThat(fetch(bob.presignedUrl()).statusCode()).isEqualTo(200);

        assertThat(deleteAccount(alice).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(snapshot(bob)).isEqualTo(before);
        assertThat(countsOf(bob)).isEqualTo(new Counts(1, 1, 2, 5, 3, 4, 4, 0, 1));
        // His binary was never touched: the URL minted before Alice's delete still serves the bytes.
        HttpResponse<byte[]> photo = fetch(bob.presignedUrl());
        assertThat(photo.statusCode()).isEqualTo(200);
        assertThat(photo.body()).isEqualTo(JPEG_BYTES);
        assertThat(outboxRowsFor(bob)).isZero();
    }

    @Test
    void wrongPasswordChangesNothing() {
        Account alice = buildRealisticAccount();
        Counts before = countsOf(alice);
        record Attempt(String label, Object body) {
        }
        List<Attempt> attempts = List.of(
                new Attempt("wrong password", new DeleteAccountRequest("definitely-not-it")),
                new Attempt("empty object", Map.of()),
                new Attempt("no body", null),
                new Attempt("blank password", new DeleteAccountRequest("")));

        for (Attempt attempt : attempts) {
            ResponseEntity<JsonNode> response = deleteWithBody(alice.token(), ME, attempt.body(), JsonNode.class);
            assertThat(response.getStatusCode()).as(attempt.label()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().get("code").asText()).as(attempt.label()).isEqualTo("INVALID_CREDENTIALS");
        }
        // No body and no Content-Type at all — the shape a client that drops DELETE bodies would send.
        HttpHeaders bare = new HttpHeaders();
        bare.setBearerAuth(alice.token());
        ResponseEntity<JsonNode> noContentType = rest.exchange(ME, HttpMethod.DELETE, new HttpEntity<>(bare), JsonNode.class);
        assertThat(noContentType.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(noContentType.getBody().get("code").asText()).isEqualTo("INVALID_CREDENTIALS");

        // Every count is unchanged AND the outbox holds no row for her prefix — the enqueue never
        // committed, so the photos are not scheduled for removal either.
        assertThat(countsOf(alice)).isEqualTo(before);
        assertThat(before.outboxRows()).isZero();
        // The failed attempts left the account fully usable…
        assertThat(get(alice.token(), "/api/v1/items", JsonNode.class).getBody().get("content")).hasSize(3);
        // …and the right password still deletes it.
        assertThat(deleteAccount(alice).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(countsOf(alice).users()).isZero();
    }

    @Test
    void deletedAccountRefreshTokenCannotResurrectTheSession() {
        Account alice = buildRealisticAccount();
        String staleAccess = alice.token();
        String refreshToken = alice.tokens().refreshToken();

        assertThat(deleteAccount(alice).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The refresh_tokens rows cascaded away with the user row: that IS the revocation.
        ResponseEntity<JsonNode> refreshed = rest.postForEntity("/api/v1/auth/refresh",
                new RefreshRequest(refreshToken), JsonNode.class);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(refreshed.getBody().get("code").asText()).isEqualTo("TOKEN_INVALID");
        assertThat(count("select count(*) from refresh_tokens where user_id = ?", alice.userId())).isZero();

        // Honest limitation, pinned on purpose: the stateless access token still decodes for the
        // rest of its 15-minute TTL. Reads see nothing (the rows are gone), writes fail on the
        // users FK — no leak, but not a session kill.
        ResponseEntity<JsonNode> items = get(staleAccess, "/api/v1/items", JsonNode.class);
        assertThat(items.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(items.getBody().get("content")).isEmpty();
        ResponseEntity<JsonNode> write = post(staleAccess, "/api/v1/spaces",
                new CreateSpaceRequest("Ghost", null, SpaceType.OTHER), JsonNode.class);
        assertThat(write.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(write.getBody().get("code").asText()).isEqualTo("CONFLICT");
        // A second delete with the stale token: the user row is gone, so it is the uniform 401.
        ResponseEntity<JsonNode> again = deleteWithBody(staleAccess, ME, new DeleteAccountRequest(PASSWORD), JsonNode.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(again.getBody().get("code").asText()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void photoObjectsAreRemovedByTheJanitorAfterTheAccountIsDeleted() throws Exception {
        Account alice = buildRealisticAccount();
        assertThat(fetch(alice.presignedUrl()).statusCode()).isEqualTo(200);
        for (String key : alice.objectKeys()) {
            assertThat(minioAdapter.exists(minioAdapter.bucket(), key)).as(key).isTrue();
        }

        assertThat(deleteAccount(alice).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Zero MinIO calls happened inside the delete; the janitor drains the outbox on its own
        // schedule (2 s in the test profile), removing the object before dropping the row.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .until(() -> outboxRowsFor(alice) == 0);
        assertThat(fetch(alice.presignedUrl()).statusCode()).isNotEqualTo(200);
        for (String key : alice.objectKeys()) {
            assertThat(minioAdapter.exists(minioAdapter.bucket(), key)).as(key).isFalse();
        }
    }

    @Test
    void assistantMessagesOfEveryOutcomeGoWithTheAccount() {
        Account alice = buildRealisticAccount();                       // already holds one NEEDS_CONFIRMATION row
        UUID office = alice.spaceIds().get(1);
        RememberResponse created = remember(alice.token(), "I put my charger in the desk drawer", office);
        assertThat(created.status()).isEqualTo(RememberResponse.Status.CREATED);
        UUID charger = created.item().id();
        uploadJpeg(alice.token(), charger, true);
        ResponseEntity<AssistantSearchResponse> searched = post(alice.token(), "/api/v1/assistant/search",
                new AssistantSearchRequest("Where is my charger?"), AssistantSearchResponse.class);
        assertThat(searched.getStatusCode()).isEqualTo(HttpStatus.OK);
        insertFailedAssistantRow(alice.userId());
        assertThat(count("select count(*) from assistant_messages where user_id = ?", alice.userId())).isEqualTo(4);
        assertThat(count("select count(*) from assistant_messages where item_id = ?", charger)).isEqualTo(1);
        assertThat(count("select count(*) from assistant_messages where space_id = ?", office)).isEqualTo(1);

        assertThat(deleteAccount(alice).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // Every outcome is gone — by user, by item and by space — together with the item the
        // assistant created, and its photo joined the outbox with the other four.
        assertThat(count("select count(*) from assistant_messages where user_id = ?", alice.userId())).isZero();
        assertThat(count("select count(*) from assistant_messages where item_id = ?", charger)).isZero();
        assertThat(count("select count(*) from assistant_messages where space_id = ?", office)).isZero();
        assertThat(count("select count(*) from items where id = ?", charger)).isZero();
        assertThat(countsOf(alice).users()).isZero();
        assertThat(outboxRowsFor(alice)).isEqualTo(5);
    }

    @Test
    void aDeepTreeAndAManyFileAccountDeleteInAConstantNumberOfStatements() {
        Account small = buildAccount(1, 2, 3);
        Account large = buildAccount(2, 8, 30);
        assertThat(countIn("locations", "space_id", large.spaceIds())).isEqualTo(16);
        assertThat(large.objectKeys()).hasSize(30);
        // The assistant rows go in ONE statement too: twenty of them must not move the count.
        for (int i = 0; i < 20; i++) {
            insertFailedAssistantRow(large.userId());
        }
        Statistics statistics = statistics();

        long statementsForSmall = measureDeletion(small, statistics);
        long statementsForLarge = measureDeletion(large, statistics);

        // The 204 on the 8-level chains is what guards the NO-ACTION/end-of-statement assumption:
        // if the parent FK ever became RESTRICT, the single-statement forest delete would fail here.
        assertThat(countsOf(large)).extracting(Counts::users, Counts::spaces, Counts::locations,
                Counts::items, Counts::history, Counts::files).containsOnly(0);
        // Hibernate counts every statement the cascade issued (the advisory locks run on a plain
        // JdbcTemplate and scale with SPACES, not data). Items, files and locations grew tenfold;
        // the statement count must not move at all, or a per-row path has crept in.
        assertThat(statementsForLarge)
                .as("statements: small=%d large=%d", statementsForSmall, statementsForLarge)
                .isEqualTo(statementsForSmall);
    }

    // ---------------------------------------------------------------- billing (wave 2)

    @Test
    void anAccountWithALiveSubscriptionLeavesACancellationBehindWhenItIsDeleted() {
        // Google Play does NOT cancel a subscription when a user deletes their app account. Before
        // this wave, DELETE /users/me removed everything and left the person being billed for a
        // product they could no longer sign in to.
        Account alice = buildAccount(1, 1, 0);
        String purchaseToken = "fake-active-pro-" + UUID.randomUUID();
        buy(alice.token(), purchaseToken, "whereis_pro_annual");
        awaitQuietJanitorWindow();

        assertThat(deleteAccount(alice).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // The user_subscriptions row cascaded away with the account; the OUTBOX row did not, and
        // deliberately carries no user_id and no foreign key — the user is gone by the time the
        // janitor reads it, and an FK would make the queue undrainable in exactly that case.
        assertThat(count("select count(*) from user_subscriptions where user_id = ?", alice.userId()))
                .isZero();
        assertThat(jdbc.queryForList(
                "select product_id, reason from play_cancellation_queue where purchase_token = ?",
                purchaseToken))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.get("product_id")).isEqualTo("whereis_pro_annual");
                    assertThat(row.get("reason")).isEqualTo("ACCOUNT_DELETED");
                });
    }

    @Test
    void deletingTheAccountAlsoPurgesTheRtdnLedgerRowsItCanStillBeLinkedTo() {
        // THE GAP THIS CLOSES. play_notifications had no delete of any kind, so the table kept
        // Google's purchase tokens, order ids, product ids and raw payload jsonb indefinitely —
        // outliving the "hard delete" /legal/delete-account promises. There is no user_id and no
        // foreign key on that table by V10's design (a notification can arrive for a token this
        // server has never seen), so the ONLY linkage is the purchase token, and the join that
        // resolves it goes through user_subscriptions — which is why the purge runs inside the
        // deletion transaction and before the users cascade.
        Account alice = buildAccount(1, 1, 0);
        String aliceToken = "fake-active-pro-" + UUID.randomUUID();
        buy(alice.token(), aliceToken, "whereis_pro_annual");

        Account bob = buildAccount(1, 1, 0);
        String bobToken = "fake-active-pro-" + UUID.randomUUID();
        buy(bob.token(), bobToken, "whereis_pro_annual");

        // Two ledger rows for Alice's token and one for Bob's. Written through the real push
        // endpoint, so the rows are exactly the shape the handler produces.
        pushRenewal(aliceToken, nowMillis());
        pushRenewal(aliceToken, nowMillis() + 1);
        pushRenewal(bobToken, nowMillis());
        assertThat(ledgerRowsFor(aliceToken)).isEqualTo(2);
        assertThat(ledgerRowsFor(bobToken)).isEqualTo(1);
        awaitQuietJanitorWindow();

        assertThat(deleteAccount(alice).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(ledgerRowsFor(aliceToken))
                .as("every notification linkable to the deleted account goes with it")
                .isZero();
        assertThat(ledgerRowsFor(bobToken))
                .as("another subscriber's ledger is untouched — the purge is scoped by token, not global")
                .isEqualTo(1);
        // And the cancellation outbox is NOT what was purged: the token has to survive there for
        // the retry window the public page states, or Google keeps billing a deleted account.
        assertThat(count("select count(*) from play_cancellation_queue where purchase_token = ?",
                aliceToken))
                .isEqualTo(1);
    }

    @Test
    void theRetentionSweepAgesOutWhatTheAccountPurgeCannotReachAndKeepsTheRest() {
        // The second half, and it exists because the first one cannot see these rows at all: a
        // notification for a token no account ever claimed, and a notification that arrives AFTER
        // the deletion, when its token no longer resolves to anybody. Without this the ledger grows
        // forever whatever account deletion does.
        // No local subscription row for either: the handler records NO_LOCAL_ROW, which is exactly
        // the row account deletion can never attribute to anybody.
        String orphanToken = "fake-active-pro-" + UUID.randomUUID();
        pushRenewal(orphanToken, nowMillis());
        assertThat(ledgerRowsFor(orphanToken)).isEqualTo(1);

        String recentToken = "fake-active-pro-" + UUID.randomUUID();
        pushRenewal(recentToken, nowMillis());

        // Age the first one past the window the public pages state. received_at is our own clock and
        // is what the sweep gates on — not Google's event time, which V11 lets a MALFORMED row omit.
        jdbc.update("update play_notifications set received_at = now() - interval '31 days'"
                + " where purchase_token = ?", orphanToken);

        notificationJanitor.runOnce();

        assertThat(ledgerRowsFor(orphanToken)).as("older than the retention window").isZero();
        assertThat(ledgerRowsFor(recentToken))
                .as("inside the window: a redelivery must still collide with the primary key")
                .isEqualTo(1);
    }

    @Test
    void deletingTwiceOverTheSameUndrainedTokenStillAnswers204() {
        // THE SEQUENCE THAT WOULD HAVE BROKEN THE PLAY-MANDATED ENDPOINT. ux_user_subscriptions_
        // purchase_token is global, so two LIVE accounts can never hold the same token — the
        // reachable collision is sequential: A enqueues T and is deleted (freeing T), the janitor
        // has not drained, the same person re-registers as B and the client re-posts T, B deletes.
        // A plain INSERT would hit ux_play_cancellation_queue_token inside AccountDeletionService's
        // single @Transactional method, roll the WHOLE cascade back and answer 409 with nothing
        // deleted. ON CONFLICT DO NOTHING makes the second enqueue a silent no-op.
        Account alice = buildAccount(1, 1, 0);
        String purchaseToken = "fake-active-pro-" + UUID.randomUUID();
        buy(alice.token(), purchaseToken, "whereis_pro_annual");
        awaitQuietJanitorWindow();
        assertThat(deleteAccount(alice).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Account bob = buildAccount(1, 1, 0);
        assertThat(buy(bob.token(), purchaseToken, "whereis_pro_annual").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertThat(deleteAccount(bob).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(count("select count(*) from play_cancellation_queue where purchase_token = ?",
                purchaseToken))
                .isEqualTo(1);
        assertThat(count("select count(*) from users where id = ?", bob.userId())).isZero();
    }

    @Test
    void theJanitorDoesNotCancelATokenThatBelongsToALiveAccountAgain() {
        // The legal page explicitly invites the person to register again. Wave 1's PurchaseSyncer
        // then posts the same token on the FIRST foreground — cancel only turns auto-renew off, so
        // queryPurchasesAsync keeps returning it for the rest of the paid term. Cancelling then
        // would turn auto-renew off for somebody who did not ask, with no notification anywhere.
        Account alice = buildAccount(1, 1, 0);
        String purchaseToken = "fake-active-pro-" + UUID.randomUUID();
        buy(alice.token(), purchaseToken, "whereis_pro_annual");
        awaitQuietJanitorWindow();
        deleteAccount(alice);

        Account bob = buildAccount(1, 1, 0);
        assertThat(buy(bob.token(), purchaseToken, "whereis_pro_annual").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ((FakePlaySubscriptionsApi) play).reset();
        makeDue(purchaseToken);

        janitor.runOnce();

        assertThat(((FakePlaySubscriptionsApi) play).cancelledTokens()).doesNotContainKey(purchaseToken);
        assertThat(count("select count(*) from play_cancellation_queue where purchase_token = ?",
                purchaseToken))
                .isZero();
        // Bob is still a paying subscriber, and nothing touched his entitlement. (His BADGE reads
        // UNLIMITED because buildAccount grants that so the fixtures can exceed the free tier —
        // which is exactly the case the "Manage subscription" control must survive: an account with
        // a paid subscription AND a higher operator grant is still being charged.)
        JsonNode subscription = get(bob.token(), "/api/v1/users/me/plan", JsonNode.class)
                .getBody().get("subscription");
        assertThat(subscription.get("tier").asText()).isEqualTo("PRO");
        assertThat(subscription.get("entitling").asBoolean()).isTrue();
    }

    @Test
    void theJanitorCancelsAtGoogleAndClearsTheQueueForAnAccountThatStayedDeleted() {
        Account alice = buildAccount(1, 1, 0);
        String purchaseToken = "fake-active-pro-" + UUID.randomUUID();
        buy(alice.token(), purchaseToken, "whereis_pro_annual");
        awaitQuietJanitorWindow();
        deleteAccount(alice);
        ((FakePlaySubscriptionsApi) play).reset();
        makeDue(purchaseToken);

        janitor.runOnce();

        assertThat(((FakePlaySubscriptionsApi) play).cancelledTokens())
                .containsEntry(purchaseToken, "whereis_pro_annual");
        assertThat(count("select count(*) from play_cancellation_queue where purchase_token = ?",
                purchaseToken))
                .isZero();
    }

    /**
     * Backdates the queue row so one hand-driven sweep is deterministic. {@code next_attempt_at}
     * defaults to the DATABASE's {@code now()} and the janitor compares against the JVM's, and the
     * Testcontainers clock can sit a moment ahead of the host's — irrelevant in production, where
     * the sweep runs every five minutes, but enough to make a single pass flaky here.
     */
    private void makeDue(String purchaseToken) {
        jdbc.update("update play_cancellation_queue set next_attempt_at = now() - interval '1 minute'"
                + " where purchase_token = ?", purchaseToken);
    }

    private int ledgerRowsFor(String purchaseToken) {
        return count("select count(*) from play_notifications where purchase_token = ?", purchaseToken);
    }

    /** One SUBSCRIPTION_RENEWED (type 2) push, through both auth checks, with a fresh message id. */
    private void pushRenewal(String purchaseToken, long eventTimeMillis) {
        ResponseEntity<String> pushed = postRtdn(UUID.randomUUID().toString(),
                subscriptionNotification(eventTimeMillis, 2, purchaseToken));
        assertThat(pushed.getStatusCode().is2xxSuccessful())
                .as("the push must be acked, not nacked: %s", pushed.getStatusCode())
                .isTrue();
    }

    private static long nowMillis() {
        return System.currentTimeMillis();
    }

    private ResponseEntity<JsonNode> buy(String token, String purchaseToken, String productId) {
        return post(token, "/api/v1/users/me/plan/purchases",
                new az.technest.whereis.plan.dto.PurchaseVerificationRequest(purchaseToken, productId),
                JsonNode.class);
    }

    private long measureDeletion(Account account, Statistics statistics) {
        awaitQuietJanitorWindow();
        statistics.clear();
        ResponseEntity<Void> response = deleteAccount(account);
        long statements = statistics.getPrepareStatementCount();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        return statements;
    }
}
