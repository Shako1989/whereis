package az.technest.whereis.item;

/** Counts returned by {@link ItemService#deleteAllForUser}; logged by the account-deletion cascade. */
public record ItemDeletionSummary(int items, int filesEnqueued) {
}
