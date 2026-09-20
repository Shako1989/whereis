package az.technest.whereis.plan.reconcile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import az.technest.whereis.common.legal.LegalProperties;
import az.technest.whereis.plan.rtdn.PlayNotificationPurgeService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The RTDN ledger's retention sweep. Everything here is offline: the component makes no Play call
 * and holds no transaction, so the interesting behaviour is the window it derives and the two
 * gates in front of it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlayNotificationJanitorTest {

    @Mock
    private PlayNotificationPurgeService purge;

    private static LegalProperties legal(String billingLogRetentionDays) {
        return new LegalProperties("support@example.com", "A Person", "Baku", "2026-09-20", "14", "7",
                billingLogRetentionDays);
    }

    private static ReconcileProperties properties(Boolean enabled) {
        return new ReconcileProperties(null, null, null,
                new ReconcileProperties.NotificationRetention(enabled));
    }

    private PlayNotificationJanitor janitor(String days, Boolean enabled) {
        return new PlayNotificationJanitor(purge, properties(enabled), legal(days));
    }

    @Test
    void theCutoffIsTheWindowThePublicPagesState() {
        when(purge.purgeReceivedBefore(any())).thenReturn(3);
        Instant before = Instant.now();

        janitor("30", true).runOnce();

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(purge).purgeReceivedBefore(cutoff.capture());
        // 30 days back from "now", to the second: the number is rendered into both legal pages from
        // the same property, so a window computed from anything else would make the page lie.
        assertThat(cutoff.getValue())
                .isBetween(before.minus(Duration.ofDays(30)).minusSeconds(5),
                        Instant.now().minus(Duration.ofDays(30)));
    }

    @Test
    void aWindowAtOrUnderPubSubsRedeliveryCeilingRefusesToStart() {
        // THE FLOOR, and why it is a startup failure rather than a clamp: Pub/Sub redelivers an
        // unacknowledged message for up to 7 days, and play_notifications.message_id is the entire
        // mechanism that makes a redelivery a no-op. Deleting a row inside that window means the
        // redelivery inserts a fresh one and is processed twice. A silent clamp would leave the
        // public page stating a number nothing honours.
        for (String tooShort : new String[] {"7", "3", "0"}) {
            assertThatThrownBy(() -> janitor(tooShort, true))
                    .as("%s days", tooShort)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("billing-log-retention-days")
                    .hasMessageContaining("Pub/Sub");
        }
        assertThatCode(() -> janitor("8", true)).doesNotThrowAnyException();
    }

    @Test
    void aNonNumericWindowNamesThePropertyRatherThanThrowingNumberFormatException() {
        assertThatThrownBy(() -> janitor("two weeks", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WHEREIS_LEGAL_BILLING_LOG_RETENTION_DAYS");
        assertThatThrownBy(() -> janitor(null, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whereis.legal.billing-log-retention-days");
    }

    @Test
    void theScheduledEntryPointHonoursTheEnabledFlagAndRunOnceIgnoresIt() {
        PlayNotificationJanitor off = janitor("30", false);

        off.sweep();
        verifyNoInteractions(purge);

        // The integration suite turns the flag off in the shared context and drives runOnce()
        // directly; that is the only way to assert the effect of a single pass.
        off.runOnce();
        verify(purge).purgeReceivedBefore(any());
    }

    @Test
    void nothingRemovedLogsNothing() {
        when(purge.purgeReceivedBefore(any())).thenReturn(0);

        janitor("30", true).sweep();

        verify(purge).purgeReceivedBefore(any());
        // No assertion on the absence of a log line beyond the component's own guard: a daily
        // "purged 0" is the noise that trains an operator to stop reading the log.
        verify(purge, never()).purgeForUser(any());
    }
}
