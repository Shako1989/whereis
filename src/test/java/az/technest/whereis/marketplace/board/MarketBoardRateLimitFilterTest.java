package az.technest.whereis.marketplace.board;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The board's rate limiter, driven directly.
 *
 * <p><strong>Why this is a unit test and not an integration test.</strong> The limiter is keyed on
 * the client address, and {@code TestRestTemplate} cannot vary it — every request in the whole
 * integration suite arrives from 127.0.0.1 and therefore shares ONE slot. That is why
 * {@code AbstractIntegrationTest} raises both budgets to 100000: production's 30 reads/minute was
 * an undeclared ceiling on how many board ITs could ever exist, and the suite was already within a
 * few requests of it. Raising them was right, and it left the limiter exercised by nothing at all.
 * A mock request can say it came from anywhere, so this is where the budgets, the per-address
 * separation, the window and the bulkhead are actually checked.
 */
class MarketBoardRateLimitFilterTest {

    private static final String BOARD = MarketBoardController.PATH + "/listings";

    /**
     * Built the way Spring Boot builds the one it injects, JSR-310 module included. A bare
     * {@code new ObjectMapper()} cannot serialize {@code ApiError.timestamp} at all — so the 429
     * body really does depend on the injected mapper, and a filter serializing its own response has
     * no {@code HttpMessageConverter} to fall back on.
     */
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    private MarketBoardRateLimitFilter filterWith(int reads, int reports) {
        return new MarketBoardRateLimitFilter(
                new MarketBoardRateLimitProperties(reads, reports), objectMapper);
    }

    @Test
    void theBudgetComesFromConfigurationAndTheRequestAfterItIsRefused() throws Exception {
        MarketBoardRateLimitFilter filter = filterWith(3, 5);

        assertThat(statuses(filter, "203.0.113.7", "GET", 4))
                .containsExactly(200, 200, 200, 429);
    }

    @Test
    void twoAddressesGetIndependentBudgetsSoOneVisitorCannotLockOutTheBoard() throws Exception {
        MarketBoardRateLimitFilter filter = filterWith(2, 5);

        assertThat(statuses(filter, "203.0.113.7", "GET", 3)).containsExactly(200, 200, 429);
        // The second address starts with a full budget of its own.
        assertThat(statuses(filter, "198.51.100.42", "GET", 3)).containsExactly(200, 200, 429);
    }

    @Test
    void theRefusalIsA429WithRetryAfterAndTheRateLimitedCodeTheClientBranchesOn() throws Exception {
        MarketBoardRateLimitFilter filter = filterWith(1, 5);
        drive(filter, request("203.0.113.9", "GET", BOARD), new MockFilterChain());

        MockHttpServletResponse refused = drive(filter, request("203.0.113.9", "GET", BOARD),
                new MockFilterChain());

        assertThat(refused.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        // A minute, because that is the read window — a client told to wait an hour would give up.
        assertThat(refused.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        JsonNode body = objectMapper.readTree(refused.getContentAsByteArray());
        assertThat(body.get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(body.get("status").asInt()).isEqualTo(429);
        // The filter runs before DispatcherServlet, so GlobalExceptionHandler would never see an
        // exception thrown here — this body has to be serialized by the filter itself.
        assertThat(body.get("path").asText()).isEqualTo(BOARD);
    }

    @Test
    void reportsHaveTheirOwnHourlyBudgetWhichReadsCannotSpend() throws Exception {
        MarketBoardRateLimitFilter filter = filterWith(1, 2);
        // Spend the whole read budget and then one refused read.
        assertThat(statuses(filter, "203.0.113.11", "GET", 2)).containsExactly(200, 429);

        assertThat(statuses(filter, "203.0.113.11", "POST", 3)).containsExactly(200, 200, 429);
    }

    @Test
    void aRefusedReportIsToldToComeBackInAnHourRatherThanAMinute() throws Exception {
        MarketBoardRateLimitFilter filter = filterWith(5, 1);
        drive(filter, request("203.0.113.13", "POST", BOARD + "/x/reports"), new MockFilterChain());

        MockHttpServletResponse refused = drive(filter,
                request("203.0.113.13", "POST", BOARD + "/x/reports"), new MockFilterChain());

        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("3600");
    }

    @Test
    void theFixedWindowReopensAndTheBudgetComesBack() throws Exception {
        // The window start is back-dated rather than waited out: sixty seconds of sleep in a unit
        // test is worse than reaching into the array the filter keeps it in, and this still drives
        // the production re-open branch of admit().
        MarketBoardRateLimitFilter filter = filterWith(1, 5);
        assertThat(statuses(filter, "203.0.113.15", "GET", 2)).containsExactly(200, 429);

        rewindWindow(filter, "readWindowStart", "203.0.113.15");

        assertThat(statuses(filter, "203.0.113.15", "GET", 1)).containsExactly(200);
    }

    @Test
    void anAddresslessRequestIsStillMeteredRatherThanUnlimited() throws Exception {
        // getRemoteAddr() can be null behind an odd container; slotOf() maps that onto a real slot
        // so it is budgeted like anything else instead of being waved through.
        MarketBoardRateLimitFilter filter = filterWith(1, 5);
        MockHttpServletRequest first = request(null, "GET", BOARD);
        MockHttpServletRequest second = request(null, "GET", BOARD);

        assertThat(drive(filter, first, new MockFilterChain()).getStatus()).isEqualTo(200);
        assertThat(drive(filter, second, new MockFilterChain()).getStatus()).isEqualTo(429);
    }

    @Test
    void theBulkheadAnswers503OnceFourRequestsAreAlreadyInsideTheBoard() throws Exception {
        // The board may never hold more than half the connection pool. tryAcquire, never acquire:
        // the right answer to "too busy" on a public endpoint is 503 now, not a queued request
        // holding a servlet thread and a database connection while the authenticated API starves.
        MarketBoardRateLimitFilter filter = filterWith(100, 100);
        CountDownLatch inside = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        FilterChain blocking = (req, res) -> {
            admitted.incrementAndGet();
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 4; i++) {
                String address = "203.0.113." + (100 + i);
                pool.submit(() -> drive(filter, request(address, "GET", BOARD), blocking));
            }
            assertThat(inside.await(10, TimeUnit.SECONDS)).as("four requests inside").isTrue();

            MockHttpServletResponse fifth = drive(filter, request("203.0.113.200", "GET", BOARD),
                    new MockFilterChain());

            assertThat(fifth.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
            assertThat(fifth.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
            assertThat(objectMapper.readTree(fifth.getContentAsByteArray()).get("code").asText())
                    .isEqualTo("RATE_LIMITED");
            assertThat(admitted).hasValue(4);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // The permits come back, so a burst is not a permanent outage.
        assertThat(drive(filter, request("203.0.113.201", "GET", BOARD), new MockFilterChain())
                .getStatus()).isEqualTo(200);
    }

    @Test
    void nothingOutsideTheBoardPathIsMeteredAtAll() {
        MarketBoardRateLimitFilter filter = filterWith(1, 1);

        assertThat(filter.shouldNotFilter(request("203.0.113.30", "GET", "/api/v1/items")))
                .as("the authenticated API is bounded by an account instead").isTrue();
        assertThat(filter.shouldNotFilter(request("203.0.113.30", "GET", "/actuator/health")))
                .isTrue();
        assertThat(filter.shouldNotFilter(request("203.0.113.30", "GET", BOARD))).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private List<Integer> statuses(MarketBoardRateLimitFilter filter, String address, String method,
            int count) {
        String path = "POST".equals(method) ? BOARD + "/x/reports" : BOARD;
        return java.util.stream.IntStream.range(0, count)
                .map(i -> drive(filter, request(address, method, path), new MockFilterChain())
                        .getStatus())
                .boxed()
                .toList();
    }

    private static MockHttpServletRequest request(String address, String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setRemoteAddr(address);
        return request;
    }

    private static MockHttpServletResponse drive(MarketBoardRateLimitFilter filter,
            HttpServletRequest request, FilterChain chain) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            filter.doFilterInternal(request, response, chain);
        } catch (ServletException | IOException e) {
            throw new IllegalStateException(e);
        }
        return response;
    }

    /** Back-dates one address's fixed window so the next request falls into a fresh one. */
    private static void rewindWindow(MarketBoardRateLimitFilter filter, String fieldName,
            String address) throws Exception {
        Field field = MarketBoardRateLimitFilter.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        AtomicLongArray windowStart = (AtomicLongArray) field.get(filter);
        int slot = Math.floorMod(address.hashCode(), windowStart.length());
        windowStart.set(slot, System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2));
    }
}
