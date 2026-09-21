package az.technest.whereis.marketplace.board;

import az.technest.whereis.common.error.ApiError;
import az.technest.whereis.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLongArray;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The whole defence of the public board, and it lives in the application rather than at the edge.
 *
 * <p><strong>Why not Caddy.</strong> Stock Caddy has no {@code rate_limit} directive — it is a
 * third-party module needing an {@code xcaddy} rebuild — and the Caddy in front of this service is
 * the CO-TENANT AutoParts stack's. Rebuilding and restarting another application's reverse proxy
 * for a whereis feature is a far larger blast radius than sixty lines here. What Caddy does supply,
 * and what this filter DEPENDS on, is {@code header_up X-Forwarded-For {remote_host}}: the default
 * {@code reverse_proxy} APPENDS the peer to whatever the client sent and Spring's
 * {@code ForwardedHeaderFilter} takes the FIRST entry, so without that one line any caller mints a
 * fresh budget per request with one line of curl.
 *
 * <p><strong>Why a fixed array and not a map.</strong> An unbounded per-address map is itself the
 * memory-exhaustion vector this filter exists to prevent, and every eviction policy has a
 * "fail open under load" mode. Two arrays of 4096 longs is ~64 KB, constant, with no eviction at
 * all. A hash collision means two addresses share a budget: the degradation is STRICTER limiting,
 * never unbounded memory and never an unmetered request.
 *
 * <p>The 429 body is serialized HERE, because a filter runs before {@code DispatcherServlet} and
 * {@code GlobalExceptionHandler} would never see the exception.
 */
@Component
@RequiredArgsConstructor
public class MarketBoardRateLimitFilter extends OncePerRequestFilter {

    private static final int SLOTS = 4096;
    private static final long READ_WINDOW_MILLIS = Duration.ofMinutes(1).toMillis();
    private static final long REPORT_WINDOW_MILLIS = Duration.ofHours(1).toMillis();

    /**
     * The board may never hold more than half the connection pool (8 in production). {@code
     * tryAcquire}, never {@code acquire}: the correct answer to "too busy" on a public endpoint is
     * 503 now, not a queued request holding a servlet thread and a database connection while the
     * authenticated API starves behind it.
     */
    private final Semaphore concurrent = new Semaphore(4);

    private final AtomicLongArray readWindowStart = new AtomicLongArray(SLOTS);
    private final AtomicLongArray readHits = new AtomicLongArray(SLOTS);
    private final AtomicLongArray reportWindowStart = new AtomicLongArray(SLOTS);
    private final AtomicLongArray reportHits = new AtomicLongArray(SLOTS);

    private final MarketBoardRateLimitProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(MarketBoardController.PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        boolean isReport = "POST".equalsIgnoreCase(request.getMethod());
        int slot = slotOf(request.getRemoteAddr());

        boolean allowed = isReport
                ? admit(reportWindowStart, reportHits, slot, REPORT_WINDOW_MILLIS, properties.reports())
                : admit(readWindowStart, readHits, slot, READ_WINDOW_MILLIS, properties.reads());
        if (!allowed) {
            long retryAfter = (isReport ? REPORT_WINDOW_MILLIS : READ_WINDOW_MILLIS) / 1000;
            write(request, response, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED,
                    "Too many requests. Try again shortly.", retryAfter);
            return;
        }

        if (!concurrent.tryAcquire()) {
            write(request, response, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.RATE_LIMITED,
                    "The marketplace is busy. Try again shortly.", 5);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            concurrent.release();
        }
    }

    /** Fixed window. Returns false once the slot's budget for the current window is spent. */
    private static boolean admit(AtomicLongArray windowStart, AtomicLongArray hits, int slot,
            long windowMillis, int budget) {
        long now = System.currentTimeMillis();
        long start = windowStart.get(slot);
        if (now - start >= windowMillis) {
            // A lost race here re-opens the window twice, which costs at most one extra budget in
            // that instant — the opposite failure (a window that never re-opens) is the one worth
            // preventing, and compareAndSet gives that.
            if (windowStart.compareAndSet(slot, start, now)) {
                hits.set(slot, 0);
            }
        }
        return hits.incrementAndGet(slot) <= budget;
    }

    private static int slotOf(String address) {
        int hash = address == null ? 0 : address.hashCode();
        return Math.floorMod(hash, SLOTS);
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
            ErrorCode code, String message, long retryAfterSeconds) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        objectMapper.writeValue(response.getOutputStream(),
                ApiError.of(status.value(), code, message, request.getRequestURI()));
    }
}
