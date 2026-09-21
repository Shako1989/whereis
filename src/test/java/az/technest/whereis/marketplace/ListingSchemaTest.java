package az.technest.whereis.marketplace;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.migration.Migrations;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Three decisions in V12 whose reversal would be SILENT — no build failure, no error, just a
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
