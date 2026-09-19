package az.technest.whereis.plan;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one thing {@code AccountDeletionService} does about billing: write the outbox row that makes
 * Google stop charging a person whose account no longer exists.
 *
 * <p><strong>It lives in {@code plan/} because it must.</strong>
 * {@code OwnershipScopingArchTest#onlyThePlanPackageReadsSubscriptions} forbids anything outside
 * {@code ..whereis.plan..} from depending on {@code UserSubscriptionRepository}, so {@code user/}
 * depends on this service rather than on the repository.
 *
 * <p>It reads {@code user_subscriptions} and writes {@code play_cancellation_queue} and makes
 * <strong>no Play call</strong>, which is what keeps {@code AccountDeletionService} a single
 * {@code @Transactional} method and keeps
 * {@code OwnershipScopingArchTest#noTransactionalMethodCallsThePlayPort} green. The external call
 * happens later, in {@code PlayCancellationJanitor}, from a queue row that has already committed —
 * V6's answer, reused rather than reinvented.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionCancellationService {

    private final PlayCancellationQueueRepository queue;

    /**
     * Enqueue every Play purchase this account holds, in ONE conflict-tolerant statement.
     *
     * <p>{@code REQUIRED}, deliberately: this is called from inside the deletion transaction and
     * must commit or roll back WITH it. A queue row for an account that was not deleted would
     * cancel a live subscriber's subscription; a deletion with no queue row would keep billing a
     * ghost. Neither is acceptable, and only sharing the transaction rules out both.
     *
     * @return how many cancellations were enqueued, for the deletion log line
     */
    @Transactional
    public int enqueueFor(UUID userId) {
        return queue.enqueueFor(userId);
    }
}
