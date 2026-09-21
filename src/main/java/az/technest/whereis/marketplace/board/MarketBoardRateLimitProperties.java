package az.technest.whereis.marketplace.board;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Budgets for the only endpoints in this application whose cost is not bounded by an account.
 * Properties rather than constants so the integration suite can raise them on the SHARED
 * {@code @DynamicPropertySource} instead of forking the Spring context with
 * {@code @TestPropertySource}.
 *
 * @param reads   GET/HEAD requests per address per minute
 * @param reports abuse reports per address per hour
 */
@ConfigurationProperties("whereis.market.rate-limit")
public record MarketBoardRateLimitProperties(Integer reads, Integer reports) {

    public MarketBoardRateLimitProperties {
        reads = reads == null || reads < 1 ? 30 : reads;
        reports = reports == null || reports < 1 ? 5 : reports;
    }
}
