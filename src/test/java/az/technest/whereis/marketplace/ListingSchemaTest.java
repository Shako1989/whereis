package az.technest.whereis.marketplace;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.migration.Migrations;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Decisions in V12 and V13 whose reversal would be SILENT — no build failure, no error, just a
 * different product. Each is asserted against the migration text directly.
 */
class ListingSchemaTest {

    @Test
    void theDetailedDescriptionFloorIsTheNumberTheServiceEnforces() {
        // "Detailed" is a NUMBER or it is nothing, and it exists in two places: the message the
        // user reads names it, and the CHECK is the floor no writer may go under. Two numbers for
        // one fact drift.
        Matcher bounds = Pattern.compile(
                        "char_length\\(description\\)\\s+BETWEEN\\s+(\\d+)\\s+AND\\s+(\\d+)",
                        Pattern.CASE_INSENSITIVE)
                .matcher(Migrations.effectiveCheckBody("ck_listings_description_length"));

        assertThat(bounds.find()).as("a description length CHECK").isTrue();
        assertThat(Integer.parseInt(bounds.group(1)))
                .isEqualTo(MarketplaceRules.MIN_DESCRIPTION_LENGTH);
        assertThat(Integer.parseInt(bounds.group(2)))
                .isEqualTo(MarketplaceRules.MAX_DESCRIPTION_LENGTH);
    }

    @Test
    void aHiddenListingStillOccupiesItsItemsOneActiveSlot() {
        // THE KILL-SWITCH, AND THE EASIEST THING IN THIS SCHEMA TO DESTROY BY "TIDYING". If the
        // partial index also excluded hidden_at, hiding would FREE the slot, the seller would
        // re-publish the same thing seconds later, and the operator would be playing whack-a-mole
        // against an endpoint.
        String sql = Migrations.allStatements();
        Matcher index = Pattern.compile(
                        "CREATE UNIQUE INDEX ux_listings_item_active\\s+ON listings \\(item_id\\)\\s+WHERE ([^;]+);",
                        Pattern.CASE_INSENSITIVE)
                .matcher(sql);

        assertThat(index.find()).as("ux_listings_item_active").isTrue();
        String predicate = index.group(1).trim();
        assertThat(predicate).isEqualTo("status = 'ACTIVE'");
        assertThat(predicate).doesNotContain("hidden_at");
    }

    @Test
    void theCoverForeignKeyIsNoActionRatherThanRestrict() {
        // Deleting an item fires TWO cascades from ONE statement (item_files and listings), and
        // RESTRICT is checked IMMEDIATELY — so whichever ran first could abort the whole delete and
        // break both DELETE /items/{id} and the Play-mandated DELETE /users/me for any seller.
        // NO ACTION is checked at end of statement, by which time the listing row is gone too.
        // Everything from this constraint's name up to whatever declaration follows it.
        Matcher fk = Pattern.compile(
                        "CONSTRAINT fk_listings_cover_file_same_item(.*?)(?:CONSTRAINT|\\n\\);)",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(Migrations.allStatements());

        assertThat(fk.find()).as("fk_listings_cover_file_same_item").isTrue();
        assertThat(fk.group(1))
                .as("references item_files (id, item_id) with NO ON DELETE clause, i.e. NO ACTION")
                .contains("REFERENCES item_files (id, item_id)")
                .doesNotContainIgnoringCase("ON DELETE");
    }

    /**
     * V13's placement decision. A block is recorded in a table of its OWN, and the obvious
     * "simplification" — four nullable columns on {@code users} — is what this asserts against,
     * because it would put the table holding every e-mail and every bcrypt hash inside the one
     * query in this application an unauthenticated stranger can run.
     */
    @Test
    void aSellerBlockIsItsOwnTableAndAddsNoColumnToUsers() {
        String sql = Migrations.allStatements();

        assertThat(sql).as("the blocked_sellers table").contains("CREATE TABLE blocked_sellers");
        // No migration may teach `users` about moderation. Written as "does ANY migration alter
        // users" rather than "does V13", so a later one cannot reintroduce the column quietly.
        assertThat(Pattern.compile("ALTER TABLE users\\s+ADD COLUMN\\s+(\\w+)",
                        Pattern.CASE_INSENSITIVE)
                .matcher(sql).results().map(match -> match.group(1)).toList())
                .as("columns ever added to users")
                .doesNotContain("blocked_at", "blocked_by", "marketplace_blocked_at",
                        "blocked_reason", "block_reason");
    }

    /**
     * The block's two structural obligations: it can never make the Play-mandated account deletion
     * fail, and it can never be a row that says only that somebody did something.
     */
    @Test
    void theBlockListCascadesWithTheAccountAndRecordsWhoWhenAndWhy() {
        Matcher table = Pattern.compile("CREATE TABLE blocked_sellers \\((.+?)\\n\\);",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(Migrations.allStatements());

        assertThat(table.find()).as("the blocked_sellers table").isTrue();
        String body = table.group(1);

        // ON DELETE CASCADE, not RESTRICT: a sanction must never be able to refuse DELETE /users/me.
        assertThat(body)
                .as("user_id is the primary key and cascades with the account")
                .containsPattern("user_id\\s+uuid\\s+PRIMARY KEY REFERENCES users \\(id\\) ON DELETE CASCADE");
        // Who, when and why are all NOT NULL: the row existing IS the block, so a block with no
        // author or no reason is unrepresentable rather than merely discouraged.
        assertThat(body).containsPattern("blocked_at\\s+timestamptz\\s+NOT NULL");
        assertThat(body).containsPattern("reason\\s+varchar\\(32\\)\\s+NOT NULL");
        assertThat(body).containsPattern("blocked_by\\s+varchar\\(320\\)\\s+NOT NULL");
        // And no history column: this table holds CURRENT sanctions, an unblock DELETEs the row,
        // and a ledger of past blocks is a retention decision made with the public pages.
        assertThat(body).doesNotContain("unblocked_at");
    }

    /**
     * V14's column, and the invariant that keeps the deletion outbox able to find a published copy.
     */
    @Test
    void aPublishedCopyRecordsItsOwnBucketAndNeverHalfOfThePair() {
        String sql = Migrations.allStatements();

        assertThat(sql).as("the published copy's bucket, recorded per row like item_files.bucket")
                .containsPattern("ALTER TABLE item_files\\s+ADD COLUMN published_bucket varchar\\(100\\)");
        // Both or neither. This CHECK is load-bearing rather than tidy: it is what lets
        // enqueuePublishedCopiesOfUser select published_bucket on the predicate
        // "published_object_key IS NOT NULL" and still satisfy storage_deletion_queue.bucket's
        // NOT NULL — otherwise the Play-mandated DELETE /users/me could abort with a 500.
        assertThat(Migrations.effectiveCheckBody("ck_item_files_published_pair"))
                .contains("published_object_key IS NULL")
                .contains("published_bucket IS NULL");
    }

    /**
     * What happened to a photo published BEFORE the strip existed, stated as SQL rather than left
     * implicit. Those copies are byte-identical to the originals (so they carry the GPS coordinates
     * the strip exists to remove) AND they sit in the private bucket, where no permanent public URL
     * can reach them — so V14 enqueues them for deletion and unpublishes them. The visible
     * consequence is that a pre-V14 listing stays ACTIVE and loses its picture, which is the same
     * shape the board already degrades to when the public bucket is unconfigured.
     */
    @Test
    void v14UnpublishesEveryCopyMadeBeforeTheMetadataStripExisted() {
        String v14 = Migrations.all().stream()
                .filter(migration -> migration.version() == 14)
                .findFirst().orElseThrow().sql();

        assertThat(v14)
                .as("the old copies are handed to the deletion outbox, in the PRIVATE bucket they "
                        + "are actually in")
                .containsPattern("INSERT INTO storage_deletion_queue[\\s\\S]*?f\\.bucket, "
                        + "f\\.published_object_key");
        assertThat(v14)
                .as("and the rows are unpublished, so a re-publish mints a fresh stripped copy")
                .containsPattern("UPDATE item_files[\\s\\S]*?SET published_object_key = NULL");
    }

    @Test
    void nothingInTheListingsTableNamesTheLocationTree() {
        // The absence IS the design: a listing that carried a location id would be one careless
        // mapper away from publishing "Home > Bedroom > Wardrobe" to the open internet.
        Matcher table = Pattern.compile("CREATE TABLE listings \\((.+?)\\n\\);",
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
                .matcher(Migrations.allStatements());

        assertThat(table.find()).as("the listings table").isTrue();
        assertThat(table.group(1))
                .doesNotContain("location_id")
                .doesNotContain("space_id")
                .doesNotContain("location_path");
    }
}
