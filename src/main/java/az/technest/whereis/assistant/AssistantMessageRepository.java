package az.technest.whereis.assistant;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Package-private on purpose: only {@link AssistantMessageService} talks to it. Write-only in this
 * change — there is no finder at all, let alone a by-id one, so another user's rows are unreachable
 * rather than merely forbidden (guarded by {@code OwnershipScopingArchTest}).
 * A history endpoint is a follow-up; when it arrives, its finder must be userId-scoped by name.
 */
interface AssistantMessageRepository extends JpaRepository<AssistantMessage, UUID> {

    /** Account deletion: one statement. No clearAutomatically — see {@code ItemRepository#deleteAllByUserId}. */
    @Modifying(flushAutomatically = true)
    @Query("delete from AssistantMessage m where m.userId = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
