package az.technest.whereis.plan.play;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.androidpublisher.AndroidPublisher;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * THE SINGLE MOST DANGEROUS LINE IN THIS WAVE, asserted without leaving the machine.
 *
 * <p>{@code purchases.voidedpurchases.list} defaults to {@code type=0}, which is ONE-TIME PRODUCTS
 * ONLY. Omit {@code setType(1)} and the refund sweep returns an empty list forever: every run
 * succeeds, every metric is green, and no refund is ever caught. Because that failure is invisible
 * in production, it gets a test that needs no network — the generated client CONSTRUCTS a request
 * object without executing it, so the parameters can simply be read back.
 *
 * <p>The adapter's builder is package-private (no Google type may escape it), which is why this
 * test lives here rather than beside the sweeper that calls it.
 *
 * <p><strong>Note the inversion with the notification side.</strong>
 * {@code voidedPurchaseNotification.productType} is 1 = subscription, 2 = one-time. This
 * {@code type} parameter is 0 = one-time only, 1 = one-time AND subscriptions. The same numbers,
 * opposite meanings; reconciling the two from memory silently breaks one of them.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GooglePlayVoidedRequestTest {

    @Test
    void theBuiltRequestAsksForSubscriptionsAndNotForOneTimeProducts() throws IOException {
        AndroidPublisher publisher = new AndroidPublisher.Builder(
                new NetHttpTransport(), GsonFactory.getDefaultInstance(), request -> { })
                .setApplicationName("whereis-test")
                .build();
        GooglePlaySubscriptionsApi api = new GooglePlaySubscriptionsApi(publisher,
                new PlayProperties("google", "az.technest.whereis", null, null));
        Instant end = Instant.parse("2026-09-19T00:00:00Z");
        Instant start = end.minus(Duration.ofDays(7));

        AndroidPublisher.Purchases.Voidedpurchases.List request =
                api.voidedPurchasesRequest(start, end, null);

        assertThat(request.getType()).isEqualTo(1);
        // Explicit rather than defaulted, for the same reason: partial refunds are
        // one-time-product-only, and saying so beats relying on a default that may change.
        assertThat(request.getIncludeQuantityBasedPartialRefund()).isFalse();
        assertThat(request.getStartTime()).isEqualTo(start.toEpochMilli());
        assertThat(request.getEndTime()).isEqualTo(end.toEpochMilli());
        assertThat(request.getPackageName()).isEqualTo("az.technest.whereis");
    }

    @Test
    void aPageTokenIsCarriedThroughSoPaginationCannotSilentlyStopAtPageOne() {
        AndroidPublisher publisher = new AndroidPublisher.Builder(
                new NetHttpTransport(), GsonFactory.getDefaultInstance(), request -> { })
                .setApplicationName("whereis-test")
                .build();
        GooglePlaySubscriptionsApi api = new GooglePlaySubscriptionsApi(publisher,
                new PlayProperties("google", "az.technest.whereis", null, null));

        try {
            assertThat(api.voidedPurchasesRequest(Instant.EPOCH, Instant.now(), "page-2").getToken())
                    .isEqualTo("page-2");
        } catch (IOException e) {
            throw new IllegalStateException("building a request must not touch the network", e);
        }
    }
}
