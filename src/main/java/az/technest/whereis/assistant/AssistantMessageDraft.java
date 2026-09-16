package az.technest.whereis.assistant;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Everything known about an assistant request once the provider has answered and BEFORE the row is
 * written: built by {@link AssistantService} after the AI call returns (or throws) and handed, with an
 * {@link AssistantMessageResult}, to {@link AssistantMessageService#record} once the flow has finished.
 * The outcome is not part of the draft — the result decides it.
 *
 * @param interpretation nullable: NULL for a provider failure, where there is nothing to snapshot
 * @param confidence     nullable, already normalized by {@link InterpretationSnapshots#confidenceOf}
 */
public record AssistantMessageDraft(
        UUID userId,
        AssistantMode mode,
        String message,
        AiMetadata ai,
        InterpretationSnapshot interpretation,
        BigDecimal confidence
) {

    public AssistantMessageDraft {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(ai, "ai");
    }
}
