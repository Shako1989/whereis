package az.technest.whereis.assistant;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only writer of {@code assistant_messages}. Two operations, each with a deliberate propagation:
 * <ul>
 *   <li>{@link #record} — REQUIRES_NEW: one row per assistant call in its own committed transaction,
 *       called by {@link AssistantService} strictly AFTER the flow has finished — the provider has
 *       returned or thrown, and {@link PlacementExecutor#place}'s transaction has already committed
 *       (CREATED) or rolled back (FAILED). It can therefore never affect the domain transaction, and a
 *       caller that one day wraps the request in a transaction which then rolls back still keeps the
 *       row (the FAILED row matters most when the request is failing). Cross-bean call through the
 *       Spring proxy, the same shape as {@code AuthService → RefreshTokenRevoker}: a REQUIRES_NEW
 *       method on {@code AssistantService} itself would be a self-invocation no-op.</li>
 *   <li>{@link #deleteAllForUser} — MANDATORY: domain deletion inside the one account-deletion
 *       transaction; MANDATORY makes that a runtime contract.</li>
 * </ul>
 * Neither ever contains an AI call. A failure of {@link #record} is deliberately NOT caught here: a
 * try/catch inside a REQUIRES_NEW method does not undo the rollback-only marking, so the proxy would
 * still throw {@code UnexpectedRollbackException} at the boundary — the swallow has to sit at the
 * caller of the proxy ({@code AssistantService.recordQuietly}). This change adds no read path.
 */
@Service
@RequiredArgsConstructor
public class AssistantMessageService {

    private final AssistantMessageRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AssistantMessageDraft draft, AssistantMessageResult result) {
        repository.save(AssistantMessage.builder()
                .userId(draft.userId())
                .mode(draft.mode())
                .message(draft.message())
                .outcome(result.outcome())
                .interpretation(draft.interpretation())
                .errorCode(result.errorCode())
                .provider(draft.ai().provider())
                .model(draft.ai().model())
                .promptVersion(draft.ai().promptVersion())
                .confidence(draft.confidence())
                .itemId(result.itemId())
                .spaceId(result.spaceId())
                .build());
    }

    /**
     * Account deletion, before the item cascade: the {@code users} FK cascade would remove these rows
     * anyway, but deleting them first means the ON DELETE SET NULL triggers on {@code item_id} and
     * {@code space_id} never fire during the item and space bulk deletes, and the count is exact.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int deleteAllForUser(UUID userId) {
        return repository.deleteAllByUserId(userId);
    }
}
