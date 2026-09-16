package az.technest.whereis.assistant;

/**
 * How an assistant request ended. Persisted as varchar and pinned by the
 * {@code ck_assistant_messages_outcome} CHECK in V8 — the constant names must match it byte for
 * byte ({@code AssistantOutcomeTest} guards the drift).
 *
 * <ul>
 *   <li>{@link #CREATED} — REMEMBER: the item was written; {@code item_id} is set.</li>
 *   <li>{@link #NEEDS_CONFIRMATION} — REMEMBER: understood, but no space could be settled; zero
 *       DOMAIN writes (the message row itself is not domain data).</li>
 *   <li>{@link #NOT_UNDERSTOOD} — REMEMBER: the validator rejected the interpretation;
 *       SEARCH: no usable keyword and no fallback, so no search ran.</li>
 *   <li>{@link #ANSWERED} — SEARCH: a search ran (with or without hits).</li>
 *   <li>{@link #FAILED} — the provider threw (interpretation NULL), or the placement transaction
 *       threw and rolled back after a validated interpretation; {@code error_code} is set.</li>
 * </ul>
 */
public enum AssistantOutcome {
    CREATED, NEEDS_CONFIRMATION, NOT_UNDERSTOOD, ANSWERED, FAILED
}
