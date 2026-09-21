package az.technest.whereis.marketplace.board;

import az.technest.whereis.common.util.Names;
import az.technest.whereis.marketplace.ListingNotFoundException;
import az.technest.whereis.marketplace.ListingReport;
import az.technest.whereis.marketplace.ListingReportRepository;
import az.technest.whereis.marketplace.board.dto.CreateReportRequest;
import az.technest.whereis.marketplace.board.dto.PublicListingDetail;
import az.technest.whereis.marketplace.board.dto.PublicListingPage;
import az.technest.whereis.marketplace.board.dto.PublicListingSummary;
import az.technest.whereis.storage.FileStorageService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything the anonymous board does. It has no {@code userId} parameter anywhere and never will:
 * requests on this path arrive without a principal by construction, because the chain that serves
 * them has no JWT decoder at all.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketBoardService {

    /** Half of {@code ItemService}'s 100. On a public endpoint the page size IS the throughput knob. */
    static final int MAX_PAGE_SIZE = 50;

    /** Deep OFFSET is a free way to make PostgreSQL work; 100 pages of 50 is a generous board. */
    static final int MAX_PAGE = 99;

    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MIN_QUERY_LENGTH = 2;

    /** One listing cannot absorb more than this in a day, whoever is sending them. */
    private static final int MAX_REPORTS_PER_LISTING_PER_DAY = 20;

    private final MarketBoardDao dao;
    private final ListingReportRepository reportRepository;
    private final FileStorageService fileStorageService;

    @Transactional(readOnly = true)
    public PublicListingPage browse(String query, String city, int page, int size) {
        int safePage = Math.min(Math.max(page, 0), MAX_PAGE);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        // A board client sends a request per keystroke, so a query that is too short is IGNORED
        // rather than answered 400 the way PostgresSearchService answers it — a 400 per keystroke
        // is noise, not information.
        String normalizedQuery = normalizeFilter(query, MIN_QUERY_LENGTH);
        String normalizedCity = normalizeFilter(city, 1);

        // size + 1: the extra row is `hasMore`, which is why there is no COUNT(*) anywhere.
        List<MarketBoardDao.BoardRow> rows =
                dao.browse(normalizedQuery, normalizedCity, safeSize + 1, safePage * safeSize);
        boolean hasMore = rows.size() > safeSize;
        List<MarketBoardDao.BoardRow> visible = hasMore ? rows.subList(0, safeSize) : rows;

        Map<UUID, String> images = presign(visible);
        List<PublicListingSummary> listings = new ArrayList<>(visible.size());
        for (MarketBoardDao.BoardRow row : visible) {
            listings.add(new PublicListingSummary(row.id(), row.title(), row.price(), row.currency(),
                    row.city(), images.get(row.coverFileId()), row.createdAt()));
        }
        return new PublicListingPage(listings, hasMore, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public PublicListingDetail detail(UUID listingId) {
        MarketBoardDao.BoardRow row = dao.findVisible(listingId)
                .orElseThrow(ListingNotFoundException::new);
        String imageUrl = presign(List.of(row)).get(row.coverFileId());
        return new PublicListingDetail(row.id(), row.title(), row.description(), row.price(),
                row.currency(), row.city(), row.phone(), imageUrl, row.createdAt());
    }

    /**
     * Records a complaint, and answers the SAME 202 whatever happened — written, dropped over the
     * per-listing cap, or filed against a listing that is not visible. An anonymous endpoint must
     * never tell a prober anything, and one status means the decision table is one line.
     */
    @Transactional
    public void report(UUID listingId, CreateReportRequest request) {
        if (dao.findVisible(listingId).isEmpty()) {
            return;
        }
        Instant since = Instant.now().minus(Duration.ofDays(1));
        if (reportRepository.countByListingIdAndReportedAtAfter(listingId, since)
                >= MAX_REPORTS_PER_LISTING_PER_DAY) {
            log.warn("Listing {} is over its daily report cap; dropping a report", listingId);
            return;
        }
        reportRepository.save(ListingReport.builder()
                .listingId(listingId)
                .reason(request.reason())
                .note(Names.clean(request.note()))
                .build());
    }

    /**
     * Presigns the PUBLISHED copies — never {@code primaryImages}, whose contract is that the ids
     * came from a userId-scoped finder. These ids come from {@code listings.cover_file_id}, which
     * only an authenticated publish could write. ONE statement for the whole page.
     */
    private Map<UUID, String> presign(List<MarketBoardDao.BoardRow> rows) {
        List<UUID> fileIds = rows.stream().map(MarketBoardDao.BoardRow::coverFileId).toList();
        return fileStorageService.presignPublished(fileIds);
    }

    private static String normalizeFilter(String raw, int minimumLength) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.length() > MAX_QUERY_LENGTH ? raw.substring(0, MAX_QUERY_LENGTH) : raw;
        String normalized = Names.normalize(trimmed);
        return normalized == null || normalized.length() < minimumLength ? null : normalized;
    }
}
