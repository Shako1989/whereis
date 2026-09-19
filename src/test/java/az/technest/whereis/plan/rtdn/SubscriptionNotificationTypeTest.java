package az.technest.whereis.plan.rtdn;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.plan.rtdn.SubscriptionNotificationType.RtdnAction;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * THE STATE-MACHINE TABLE, pinned integer by integer.
 *
 * <p>Adding a constant without a branch must be a FAILURE rather than a silent default, which is
 * what a parameterised test over every documented wire value buys. The unknown values are just as
 * important in the other direction: they must reach REFRESH, because a type Google adds after this
 * ships is answered by re-reading the authoritative state, never by ignoring it.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SubscriptionNotificationTypeTest {

    @ParameterizedTest
    @CsvSource({
            "1,  SUBSCRIPTION_RECOVERED,               REFRESH",
            "2,  SUBSCRIPTION_RENEWED,                 REFRESH",
            "3,  SUBSCRIPTION_CANCELED,                REFRESH",
            "4,  SUBSCRIPTION_PURCHASED,               REFRESH",
            "5,  SUBSCRIPTION_ON_HOLD,                 REFRESH",
            "6,  SUBSCRIPTION_IN_GRACE_PERIOD,         REFRESH",
            "7,  SUBSCRIPTION_RESTARTED,               REFRESH",
            "8,  SUBSCRIPTION_PRICE_CHANGE_CONFIRMED,  REFRESH",
            "9,  SUBSCRIPTION_DEFERRED,                REFRESH",
            "10, SUBSCRIPTION_PAUSED,                  REFRESH",
            "11, SUBSCRIPTION_PAUSE_SCHEDULE_CHANGED,  REFRESH",
            "12, SUBSCRIPTION_REVOKED,                 REVOKE",
            "13, SUBSCRIPTION_EXPIRED,                 REFRESH",
            "19, SUBSCRIPTION_PRICE_CHANGE_UPDATED,    REFRESH",
            "20, SUBSCRIPTION_PENDING_PURCHASE_CANCELED, REFRESH"
    })
    void everyDocumentedTypeMapsToItsConstantAndItsAction(int wire, SubscriptionNotificationType expected,
                                                          RtdnAction action) {
        assertThat(SubscriptionNotificationType.of(wire)).isEqualTo(expected);
        assertThat(expected.wire()).isEqualTo(wire);
        assertThat(SubscriptionNotificationType.actionOf(wire)).isEqualTo(action);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 14, 15, 16, 17, 18, 21, 9999})
    void aTypeThisBuildHasNeverHeardOfIsRefreshedRatherThanIgnored(int wire) {
        // The whole reason the table is short: re-reading the authoritative state cannot be wrong,
        // and SubscriptionState.fromWire still maps anything unmapped to UNKNOWN, which DENIES — so
        // even the default branch cannot accidentally entitle.
        assertThat(SubscriptionNotificationType.of(wire)).isNull();
        assertThat(SubscriptionNotificationType.actionOf(wire)).isEqualTo(RtdnAction.REFRESH);
    }

    @Test
    void anAbsentTypeIsRefreshedToo() {
        assertThat(SubscriptionNotificationType.actionOf(null)).isEqualTo(RtdnAction.REFRESH);
        assertThat(SubscriptionNotificationType.of(null)).isNull();
    }

    @Test
    void revocationIsTheOnlyTypeAppliedWithoutAskingGoogle() {
        // If a second REVOKE ever appears here it must be a deliberate decision, not a slip: the
        // revoke path writes voided_at from the notification ALONE, with no corroboration, and
        // voided_at is write-once.
        assertThat(Arrays.stream(SubscriptionNotificationType.values())
                .filter(type -> type.action() == RtdnAction.REVOKE)
                .toList())
                .containsExactly(SubscriptionNotificationType.SUBSCRIPTION_REVOKED);
    }

    @Test
    void noTwoConstantsShareAWireValue() {
        assertThat(Arrays.stream(SubscriptionNotificationType.values())
                .map(SubscriptionNotificationType::wire)
                .distinct()
                .count())
                .isEqualTo(SubscriptionNotificationType.values().length);
    }
}
