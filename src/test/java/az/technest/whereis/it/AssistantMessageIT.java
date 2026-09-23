package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.assistant.dto.AssistantSearchRequest;
import az.technest.whereis.assistant.dto.AssistantSearchResponse;
import az.technest.whereis.assistant.dto.RememberRequest;
import az.technest.whereis.assistant.dto.RememberResponse;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.SpaceResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * V8: every assistant request past {@code sanitize} leaves exactly one {@code assistant_messages}
 * row owned by the caller — CREATED linked to its item and space, the zero-write
 * NEEDS_CONFIRMATION, NOT_UNDERSTOOD with the raw interpretation, and the SEARCH outcomes — and
 * another user's rows are unreachable.
 *
 * <p>Rows are asserted straight from the table, with the {@code jsonb} projected into readable
 * columns: there is no read API for them (the repository has no finder at all), and reading through
 * {@code ->>} is what proves the {@code interpretation} column really holds a JSON object rather
 * than a double-encoded string. Every IT boots with {@code ddl-auto: validate}, so these assertions
 * also pin the {@code jsonb}/{@code numeric}/{@code text} mappings against V8.
 */
class AssistantMessageIT extends AbstractIntegrationTest {

    private static final String REMEMBER = "/api/v1/assistant/remember";
    private static final String SEARCH = "/api/v1/assistant/search";
    private static final String PASSPORT_SENTENCE = "I put my passport in the bedroom wardrobe top drawer";
    private static final String CHARGER_SENTENCE = "I put my charger in the desk drawer";

    private RememberResponse remember(String token, String message, UUID spaceId) {
        ResponseEntity<RememberResponse> response =
                post(token, REMEMBER, new RememberRequest(message, spaceId, null), RememberResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private AssistantSearchResponse search(String token, String query) {
        ResponseEntity<AssistantSearchResponse> response =
                post(token, SEARCH, new AssistantSearchRequest(query), AssistantSearchResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    /** Newest first, straight from the table, with the jsonb projected into readable columns. */
    private List<Map<String, Object>> rowsOf(UUID userId) {
        return jdbc.queryForList("""
                select id, mode, outcome, message, provider, model, prompt_version, confidence, error_code,
                       item_id, space_id, created_at,
                       jsonb_typeof(interpretation)      as json_type,
                       interpretation->>'validated'      as validated,
                       interpretation->>'itemName'       as item_name,
                       interpretation->>'spaceName'      as space_name,
                       interpretation->>'offeredSpaces'  as offered_spaces,
                       interpretation->>'usedFallback'   as used_fallback,
                       interpretation->>'keywords'       as keywords,
                       interpretation->>'matches'        as matches,
                       interpretation->>'offeredItemCount' as offered_item_count,
                       interpretation->>'withoutAi'      as without_ai
                from assistant_messages
                where user_id = ?
                order by created_at desc, id desc
                """, userId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int locationCount(UUID userId) {
        return count("select count(*) from locations where space_id in (select id from spaces where user_id = ?)",
                userId);
    }

    private static Instant createdAtOf(Map<String, Object> row) {
        return ((Timestamp) row.get("created_at")).toInstant();
    }

    @Test
    void aCreatedRememberLeavesOneRowLinkedToItsItemAndSpace() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);

        RememberResponse remembered = remember(token, PASSPORT_SENTENCE, null);
        assertThat(remembered.status()).isEqualTo(RememberResponse.Status.CREATED);
        UUID itemId = remembered.item().id();

        List<Map<String, Object>> rows = rowsOf(userId);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row)
                .containsEntry("mode", "REMEMBER")
                .containsEntry("outcome", "CREATED")
                .containsEntry("message", PASSPORT_SENTENCE)
                .containsEntry("provider", "mock")
                .containsEntry("model", "mock-rules")
                .containsEntry("prompt_version", "mock-1")
                .containsEntry("item_id", itemId)
                .containsEntry("space_id", home.id());
        assertThat(row.get("error_code")).isNull();
        assertThat((BigDecimal) row.get("confidence")).isEqualByComparingTo("0.9");
        // The link is a real FK to a real row, not just a matching uuid.
        assertThat(count("select count(*) from items where id = ? and user_id = ?", itemId, userId)).isEqualTo(1);

        // A real jsonb OBJECT, not a double-encoded string: the ->> projections must read through.
        // This is the assertion that catches the String + SqlTypes.JSON trap.
        assertThat(row)
                .containsEntry("json_type", "object")
                .containsEntry("validated", "true")
                .containsEntry("item_name", "Passport");
        // The space names the model was offered — the field that tells a wrong space apart from a
        // space the model was never shown (the 2026-09-16 incident).
        assertThat((String) row.get("offered_spaces")).contains("\"Home\"");
    }

    @Test
    void needsConfirmationWritesOnlyTheMessageRowAndTheConfirmationWritesASecondOne() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        // Ambiguity needs two spaces, which is beyond the free tier: granted account.
        grantUnlimited(userId);
        createSpace(token, "Home", SpaceType.HOME);
        SpaceResponse office = createSpace(token, "Office", SpaceType.OFFICE);
        int spacesBefore = count("select count(*) from spaces where user_id = ?", userId);

        RememberResponse asked = remember(token, CHARGER_SENTENCE, null);
        assertThat(asked.status()).isEqualTo(RememberResponse.Status.NEEDS_CONFIRMATION);

        // The message row is the ONLY write: no item, no location, and above all no space invented
        // from AI output.
        assertThat(count("select count(*) from items where user_id = ?", userId)).isZero();
        assertThat(locationCount(userId)).isZero();
        assertThat(count("select count(*) from spaces where user_id = ?", userId)).isEqualTo(spacesBefore);
        List<Map<String, Object>> rows = rowsOf(userId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("outcome", "NEEDS_CONFIRMATION")
                .containsEntry("message", CHARGER_SENTENCE)
                .containsEntry("validated", "true")
                .containsEntry("item_name", "Charger");
        assertThat(rows.getFirst().get("item_id")).isNull();
        assertThat(rows.getFirst().get("space_id")).isNull();
        assertThat(rows.getFirst().get("error_code")).isNull();
        // The model named no space — for the 2026-09-16 incident this is exactly the field to read.
        assertThat(rows.getFirst().get("space_name")).isNull();

        RememberResponse created = remember(token, CHARGER_SENTENCE, office.id());
        assertThat(created.status()).isEqualTo(RememberResponse.Status.CREATED);

        // Two rows for one user intent, on purpose: the honest record of what happened.
        rows = rowsOf(userId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("outcome", "CREATED")
                .containsEntry("item_id", created.item().id())
                .containsEntry("space_id", office.id());
        assertThat(rows.get(1)).containsEntry("outcome", "NEEDS_CONFIRMATION");
        assertThat(rows).allSatisfy(r -> assertThat(r).containsEntry("message", CHARGER_SENTENCE));
        assertThat(count("select count(*) from items where user_id = ?", userId)).isEqualTo(1);
        // Only the confirmed request created anything, and it landed in the space that was chosen.
        assertThat(count("select count(*) from locations where space_id = ?", office.id())).isEqualTo(2);
    }

    @Test
    void notUnderstoodKeepsTheRawInterpretationAndWritesNothingElse() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        createSpace(token, "Home", SpaceType.HOME);

        RememberResponse response = remember(token, "hello there", null);
        assertThat(response.status()).isEqualTo(RememberResponse.Status.NOT_UNDERSTOOD);

        List<Map<String, Object>> rows = rowsOf(userId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst())
                .containsEntry("mode", "REMEMBER")
                .containsEntry("outcome", "NOT_UNDERSTOOD")
                .containsEntry("message", "hello there")
                .containsEntry("provider", "mock")
                .containsEntry("model", "mock-rules")
                .containsEntry("prompt_version", "mock-1")
                .containsEntry("json_type", "object")
                .containsEntry("validated", "false");
        assertThat((BigDecimal) rows.getFirst().get("confidence")).isEqualByComparingTo("0");
        assertThat(rows.getFirst().get("item_id")).isNull();
        assertThat(rows.getFirst().get("space_id")).isNull();
        assertThat(rows.getFirst().get("error_code")).isNull();
        assertThat(count("select count(*) from items where user_id = ?", userId)).isZero();
        assertThat(locationCount(userId)).isZero();
    }

    @Test
    void searchOutcomesAreRecordedWithTheKeywordsTheSearchRanWith() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        createSpace(token, "Home", SpaceType.HOME);
        assertThat(remember(token, PASSPORT_SENTENCE, null).status()).isEqualTo(RememberResponse.Status.CREATED);

        assertThat(search(token, "Where is my passport?").items()).hasSize(1);
        assertThat(search(token, "zzzz").items()).isEmpty();                     // ANSWERED, no hits
        assertThat(search(token, "a").items()).isEmpty();                        // nothing usable: no search ran

        List<Map<String, Object>> searches = rowsOf(userId).stream()
                .filter(row -> "SEARCH".equals(row.get("mode")))
                .toList();
        assertThat(searches).hasSize(3);
        assertThat(searches.get(0))
                .containsEntry("message", "a")
                .containsEntry("outcome", "NOT_UNDERSTOOD")
                .containsEntry("validated", "false");
        assertThat(searches.get(1))
                .containsEntry("message", "zzzz")
                .containsEntry("outcome", "ANSWERED")
                .containsEntry("used_fallback", "false");
        assertThat(searches.get(2))
                .containsEntry("message", "Where is my passport?")
                .containsEntry("outcome", "ANSWERED")
                .containsEntry("validated", "true")
                .containsEntry("used_fallback", "false");
        assertThat((String) searches.get(2).get("keywords")).contains("\"passport\"");
        assertThat(searches).allSatisfy(row -> {
            assertThat(row.get("item_id")).isNull();
            assertThat(row.get("space_id")).isNull();
            assertThat(row.get("confidence")).isNull();
            assertThat(row.get("error_code")).isNull();
            assertThat(row).containsEntry("provider", "mock").containsEntry("prompt_version", "mock-1");
        });
    }

    @Test
    void rowsBelongToOneUserAndAreNewestFirstWithAPerRequestTimestamp() {
        String alice = registerAndGetToken();
        UUID aliceId = subjectOf(alice);
        createSpace(alice, "Home", SpaceType.HOME);
        RememberResponse created = remember(alice, PASSPORT_SENTENCE, null);
        assertThat(created.status()).isEqualTo(RememberResponse.Status.CREATED);
        remember(alice, "hello there", null);
        search(alice, "Where is my passport?");

        // Three requests, three rows, newest first. The timestamps are strictly distinct, so the
        // order below is the created_at order and not the uuid tiebreaker's — which is the property
        // a "newest first" read path would depend on.
        List<Map<String, Object>> rows = rowsOf(aliceId);
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(row -> row.get("message"))
                .containsExactly("Where is my passport?", "hello there", PASSPORT_SENTENCE);
        assertThat(rows).extracting(row -> row.get("outcome"))
                .containsExactly("ANSWERED", "NOT_UNDERSTOOD", "CREATED");
        // Only the distinctness is asserted: rowsOf() already ORDERs BY created_at DESC, so
        // re-asserting the sort would test the test's own SQL. Distinct timestamps are what make
        // created_at alone sufficient to order the rows, which is the property that matters.
        assertThat(rows).extracting(AssistantMessageIT::createdAtOf).doesNotHaveDuplicates();

        // Tenancy: a second user has no rows of their own and no way to reach Alice's — the table has
        // no read API at all, and her item stays a 404 for him, so the sentence cannot leak that way.
        String bob = registerAndGetToken();
        UUID bobId = subjectOf(bob);
        assertThat(rowsOf(bobId)).isEmpty();
        search(bob, "passport");
        assertThat(rowsOf(bobId)).hasSize(1);
        assertThat(rowsOf(bobId).getFirst()).containsEntry("message", "passport");
        assertThat(rowsOf(aliceId)).hasSize(3);
        ResponseEntity<JsonNode> foreign = get(bob, "/api/v1/items/" + created.item().id(), JsonNode.class);
        assertThat(foreign.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(foreign.getBody().get("code").asText()).isEqualTo("ITEM_NOT_FOUND");
    }

    @Test
    void anItemIsFoundThroughTheNameTheModelPickedAndTheRowRecordsWhatItWasShown() {
        // The match-and-resolve path end to end, offline. The mock provider matches an offered
        // name that literally occurs in the sentence — not understanding, but enough to prove the
        // wiring: names are loaded, handed over, validated, looked up, and recorded.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        // A space has to exist first: with none, remember asks rather than creating, and there
        // would be no item to find.
        createSpace(token, "Home", SpaceType.HOME);
        remember(token, PASSPORT_SENTENCE, null);

        AssistantSearchResponse answer = search(token, "Where is my passport?");
        assertThat(answer.items()).extracting("name").containsExactly("Passport");

        Map<String, Object> row = rowsOf(userId).getFirst();
        assertThat(row).containsEntry("mode", "SEARCH").containsEntry("outcome", "ANSWERED");
        // What the model picked, and HOW MANY names it was shown — never which ones, because a
        // whole inventory copied onto every search row is a table that grows without bound.
        assertThat((String) row.get("matches")).contains("passport");
        assertThat(row).containsEntry("offered_item_count", "1");
    }

    @Test
    void anAccountWithNoItemsIsOfferedNothingAndTheRowSaysSo() {
        // Zero is stored as absent rather than 0, so the key simply is not in the jsonb.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);

        search(token, "Where is my passport?");

        Map<String, Object> row = rowsOf(userId).getFirst();
        assertThat(row.get("offered_item_count")).isNull();
        assertThat(row.get("matches")).isNull();
    }

    @Test
    void aOneWordSearchTheDatabaseAnswersRecordsThatNoProviderWasAsked() {
        // The cost optimisation, asserted where it is observable: the row must not name a provider
        // that was never called, and no item name can have been offered because no list was built.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        createSpace(token, "Home", SpaceType.HOME);
        remember(token, PASSPORT_SENTENCE, null);

        AssistantSearchResponse answer = search(token, "passport");
        assertThat(answer.items()).extracting("name").containsExactly("Passport");

        Map<String, Object> row = rowsOf(userId).getFirst();
        assertThat(row)
                .containsEntry("mode", "SEARCH")
                .containsEntry("outcome", "ANSWERED")
                .containsEntry("without_ai", "true")
                // AiMetadata.NONE, not the mock's triple.
                .containsEntry("provider", "none")
                .containsEntry("prompt_version", "none");
        assertThat(row.get("offered_item_count")).isNull();
        assertThat(row.get("matches")).isNull();
    }

    @Test
    void aOneWordSearchTheDatabaseCannotAnswerStillNamesTheProvider() {
        // The fall-through half. "zzzz" matches nothing, so the model is asked after all and the
        // row records who answered — otherwise a saved call and a failed one would look alike.
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        createSpace(token, "Home", SpaceType.HOME);
        remember(token, PASSPORT_SENTENCE, null);

        search(token, "zzzz");

        Map<String, Object> row = rowsOf(userId).getFirst();
        assertThat(row).containsEntry("mode", "SEARCH").containsEntry("provider", "mock");
        assertThat(row.get("without_ai")).isNull();
    }
}
