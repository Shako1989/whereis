package az.technest.whereis.plan.rtdn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Accepts exactly one configured literal bearer value, so {@code PlayRtdnIT} can drive the endpoint
 * with no network, no Google project and no Pub/Sub subscription.
 *
 * <p><strong>Refused under the {@code prod} profile</strong>, by the same {@code Environment#acceptsProfiles}
 * guard and for the same reason {@code FakePlaySubscriptionsApi} is: this endpoint can hand any
 * account any tier, and the most likely way {@code PLAY_RTDN_VERIFIER=fake} gets set in production
 * is somebody fixing a boot failure at 2am.
 *
 * <p>A blank configured literal REJECTS every request. The alternative — treating blank as
 * "no check" — would leave the endpoint protected by a URL query parameter alone, which is the one
 * thing reverse proxies log.
 */
public class FakePlayPushAuthenticator implements PlayPushAuthenticator {

    private final byte[] expected;

    public FakePlayPushAuthenticator(RtdnProperties properties) {
        this.expected = properties.fakeBearer().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean isGenuine(String bearerToken) {
        if (expected.length == 0 || bearerToken == null || bearerToken.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(expected, bearerToken.getBytes(StandardCharsets.UTF_8));
    }
}
