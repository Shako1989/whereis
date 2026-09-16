package az.technest.whereis.assistant;

/**
 * Which assistant flow a request went through. Persisted as varchar and pinned by the
 * {@code ck_assistant_messages_mode} CHECK in V8 — the constant names must match it byte for byte
 * ({@code AssistantOutcomeTest} guards the drift).
 */
public enum AssistantMode {
    REMEMBER, SEARCH
}
