package az.technest.whereis.assistant;

import java.util.Objects;
import java.util.UUID;

/**
 * How one assistant request ended, as the recorder needs it: the outcome plus the two links and the
 * error code that only some outcomes carry. The factories are the only sensible way to build one,
 * and the compact constructor rejects what the V8 CHECKs would reject ({@code item_id} only when
 * CREATED, {@code error_code} only — and always — when FAILED), so a constraint violation is
 * unrepresentable from Java instead of a surprise inside the recorder's transaction.
 *
 * @param itemId    the created item — CREATED only
 * @param spaceId   the resolved space — CREATED, and FAILED when the executor threw after resolution
 * @param errorCode {@link az.technest.whereis.common.error.ErrorCode} name or {@link #UNEXPECTED_ERROR} — FAILED only
 */
public record AssistantMessageResult(AssistantOutcome outcome, UUID itemId, UUID spaceId, String errorCode) {

    /** The error code for anything that is not an {@code ApiException}. */
    public static final String UNEXPECTED_ERROR = "UNEXPECTED_ERROR";

    public AssistantMessageResult {
        Objects.requireNonNull(outcome, "outcome");
        if (itemId != null && outcome != AssistantOutcome.CREATED) {
            throw new IllegalArgumentException("itemId is only set for CREATED, not " + outcome);
        }
        if ((errorCode != null) != (outcome == AssistantOutcome.FAILED)) {
            throw new IllegalArgumentException("errorCode is set exactly when the outcome is FAILED, not " + outcome);
        }
    }

    public static AssistantMessageResult created(UUID itemId, UUID spaceId) {
        return new AssistantMessageResult(AssistantOutcome.CREATED,
                Objects.requireNonNull(itemId, "itemId"), Objects.requireNonNull(spaceId, "spaceId"), null);
    }

    public static AssistantMessageResult needsConfirmation() {
        return new AssistantMessageResult(AssistantOutcome.NEEDS_CONFIRMATION, null, null, null);
    }

    public static AssistantMessageResult notUnderstood() {
        return new AssistantMessageResult(AssistantOutcome.NOT_UNDERSTOOD, null, null, null);
    }

    public static AssistantMessageResult answered() {
        return new AssistantMessageResult(AssistantOutcome.ANSWERED, null, null, null);
    }

    /** The provider threw: nothing was resolved, nothing to link. */
    public static AssistantMessageResult failed(String errorCode) {
        return failed(errorCode, null);
    }

    /** The executor threw after the space was resolved: the domain transaction rolled back, the space link is kept. */
    public static AssistantMessageResult failed(String errorCode, UUID spaceId) {
        return new AssistantMessageResult(AssistantOutcome.FAILED, null, spaceId,
                Objects.requireNonNull(errorCode, "errorCode"));
    }
}
