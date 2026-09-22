package az.technest.whereis.marketplace.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import az.technest.whereis.marketplace.ListingReportRepository;
import az.technest.whereis.marketplace.board.dto.PublicListingPage;
import az.technest.whereis.storage.FileStorageService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * <strong>The city filter's three cases, which are not two.</strong> V15 replaced free text with a
 * code, and the one way that goes wrong is invisible: resolve an unrecognised value to {@code null},
 * let the DAO drop the clause, and a typo — or a probe — is answered with the ENTIRE BOARD while
 * every status code and every field stays exactly right.
 *
 * <p>So: absent or blank means every city; a code-SHAPED value always becomes an equality
 * predicate, whatever it is, so a code no row holds costs one index probe and returns nothing; and
 * a value that cannot be a code at all is answered with an empty page for zero statements. The
 * invariant being pinned is <em>the clause is never dropped because a value was not recognised</em>,
 * which is why the unknown-code case asserts the DAO was CALLED rather than skipped.
 */
@ExtendWith(MockitoExtension.class)
class MarketBoardCityFilterTest {

    @Mock
    private MarketBoardDao dao;

    @Mock
    private ListingReportRepository reportRepository;

    @Mock
    private FileStorageService fileStorageService;

    @InjectMocks
    private MarketBoardService service;

    /**
     * The one that can silently invert. An unknown code reaches the query AS a filter, so the
     * answer is an empty page — never the whole board.
     */
    @Test
    void anUnknownCodeStillFiltersAndThereforeMatchesNothing() {
        when(dao.browse(isNull(), eq("NOWHERE"), anyInt(), anyInt())).thenReturn(List.of());

        PublicListingPage page = service.browse(null, "NOWHERE", 0, 20);

        assertThat(page.listings()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        // The predicate was applied rather than dropped: that is the whole assertion.
        verify(dao).browse(null, "NOWHERE", 21, 0);
        verify(dao, never()).browse(isNull(), isNull(), anyInt(), anyInt());
    }

    /**
     * The free text V15 removed cannot be a code, so it matches nothing — and costs no statement at
     * all, which is the cheapest correct answer an anonymous endpoint can give a value it does not
     * understand.
     */
    @Test
    void theFreeTextTheCodesReplacedFiltersToNothingWithoutAQuery() {
        assertThat(service.browse(null, "baki seher merkezi", 0, 20).listings()).isEmpty();
        assertThat(service.browse(null, "şəhər mərkəzi", 0, 20).listings()).isEmpty();
        assertThat(service.browse(null, "' OR 1=1 --", 0, 20).listings()).isEmpty();
        assertThat(service.browse(null, "x".repeat(10_000), 0, 20).listings()).isEmpty();

        verifyNoInteractions(dao, fileStorageService);
    }

    /**
     * <strong>The label that LOOKS like a code, and the case this design is easiest to get wrong
     * on.</strong> {@code "Bakı"} upper-cases to {@code "BAKI"} under {@code Locale.ROOT} — dotless
     * ı becomes plain I — so it passes the shape gate and IS filtered on. It matches no row, which
     * is the right answer for the old free text; what would be wrong is answering it with the whole
     * board because nothing recognised it.
     */
    @Test
    void anOldFreeTextSpellingThatFoldsToSomethingCodeShapedIsStillAFilter() {
        when(dao.browse(isNull(), eq("BAKI"), anyInt(), anyInt())).thenReturn(List.of());

        assertThat(service.browse(null, "Bakı", 0, 20).listings()).isEmpty();

        verify(dao).browse(null, "BAKI", 21, 0);
        verify(dao, never()).browse(isNull(), isNull(), anyInt(), anyInt());
    }

    /** The page echo still describes the request, so a client's pager does not jump to page 0. */
    @Test
    void anEmptyPageStillEchoesThePageAndSizeThatWereAsked() {
        PublicListingPage page = service.browse(null, "28 May metro", 3, 15);

        assertThat(page.listings()).isEmpty();
        assertThat(page.page()).isEqualTo(3);
        assertThat(page.size()).isEqualTo(15);
    }

    @Test
    void aKnownCodeReachesTheQueryExactlyAsTheTableSpellsIt() {
        when(dao.browse(isNull(), eq("BAKU"), anyInt(), anyInt())).thenReturn(List.of());

        service.browse(null, "  baku  ", 0, 20);

        // size + 1 is `hasMore`; the offset is page * size. Locale.ROOT is what makes "baku" BAKU
        // on a machine whose default locale is Azerbaijani.
        verify(dao).browse(null, "BAKU", 21, 0);
    }

    @Test
    void noCityParameterAtAllMeansEveryCity() {
        when(dao.browse(isNull(), isNull(), anyInt(), anyInt())).thenReturn(List.of());

        service.browse(null, null, 0, 20);
        // A client clearing the picker sends `?city=` — blank is "every city", not "no city".
        service.browse(null, "   ", 0, 20);

        verify(dao, times(2)).browse(isNull(), isNull(), anyInt(), anyInt());
        verify(fileStorageService, times(2)).publishedImageUrls(any());
    }

    /** The query half is untouched by V15 and still folds, because it IS user-typed text. */
    @Test
    void theKeywordHalfStillFoldsDiacriticsWhileTheCityDoesNot() {
        when(dao.browse(anyString(), eq("GANJA"), anyInt(), anyInt())).thenReturn(List.of());

        service.browse("Şkaf", "ganja", 0, 20);

        verify(dao).browse("skaf", "GANJA", 21, 0);
    }
}
