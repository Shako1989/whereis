package az.technest.whereis.marketplace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One place a buyer can collect an item: a first-order administrative unit of Azerbaijan, seeded by
 * V15 and never written by the application.
 *
 * <p><strong>REFERENCE DATA IN A TABLE, WHICH BREAKS THIS CODEBASE'S CONVENTION ON PURPOSE.</strong>
 * Every other enum-ish value here is a varchar + CHECK pinned to a Java enum — {@code SpaceType},
 * {@code LocationType}, {@code Plan}, {@code ListingStatus}, {@code SellerBlockReason} — and that
 * convention is right for values THE CODE BRANCHES ON. Nothing anywhere does
 * {@code if (city == BAKU)}: the city is a filter key and a label. Three things settle it, and V15's
 * header carries the long form: retirement is impossible with an enum (removing a constant either
 * breaks existing rows or keeps a dead constant forever, where {@link #active} takes an entry out of
 * the picker and leaves every listing that named it alone); a foreign key cannot drift from the
 * allowed set, where a CHECK can drift from the Java enum; and 75 × 3 localised names are data, not
 * three Android string files with 225 chances to mis-type a diacritic.
 *
 * <p><strong>There is no {@code ListingCity} enum and there must not be one.</strong> A Java mirror
 * of these rows would re-create exactly the drift {@code fk_listings_city} exists to make
 * impossible, and it could not express {@code active} at all.
 *
 * <p><strong>The code is the frozen half; a label is not.</strong> Administrative change is rare but
 * real — Ağdərə was created by Law No. 1043-VIQ of 5 December 2023 out of parts of Ağdam, Kəlbəcər
 * and Tərtər — so plan for "INSERT a row by migration" and never for renaming or renumbering one: a
 * stored code is what a seller SAID about where their item can be collected.
 *
 * <p><strong>Never case-map a code with the default locale.</strong> Under an {@code az} or
 * {@code tr} locale {@code "ISMAYILLI".toLowerCase()} yields a dotless-ı string that does not
 * round-trip and {@code "imishli".toUpperCase()} yields {@code "İMİŞLİ"}. Every case mapping of a
 * code in this application goes through {@link MarketCityCodes}, which names {@code Locale.ROOT};
 * {@code Names.normalize} is for user-typed names and must never be applied to a code.
 */
@Entity
@Table(name = "market_cities")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class MarketCity {

    /** Upper-snake ASCII, and the identity of the row — {@code listings.city} stores exactly this. */
    @Id
    @Column(nullable = false, updatable = false, length = 32)
    private String code;

    /**
     * All three languages on the row, because the picker serves all three and the CLIENT reads its
     * own. The server never negotiates a locale for this and never renders a label: it serves a
     * lookup table, not localised text.
     */
    @Column(name = "name_az", nullable = false, length = 64)
    private String nameAz;

    @Column(name = "name_en", nullable = false, length = 64)
    private String nameEn;

    @Column(name = "name_ru", nullable = false, length = 64)
    private String nameRu;

    /**
     * The picker's order, pre-computed as DATA so that no server needs an Azerbaijani collator and
     * no two clients can disagree. Azerbaijani collates ə, ğ, ı, ö, ş and ü outside the Latin
     * order, so a naive sort scatters Ağdam, Gədəbəy and Şəki and puts Xankəndi after Yevlax
     * instead of between Gəncə and Lənkəran.
     */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /**
     * Whether a NEW listing may name this place. {@code false} retires it from the picker while
     * every listing that already named it keeps its value and stays on the board — a seller's
     * statement about the past is not ours to rewrite, and this is the capability an enum could not
     * have had.
     */
    @Column(nullable = false)
    private boolean active;
}
