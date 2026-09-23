package az.technest.whereis.assistant;

import az.technest.whereis.assistant.InterpretationValidator.ValidatedPlacement;
import az.technest.whereis.location.ChainSegment;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Factories for {@link InterpretationSnapshot} — the ONE place that bounds what reaches the jsonb
 * column: raw provider output is capped (names 200, description 2000, 10 locations, 5 keywords,
 * control characters stripped) and the confidence conversion is NaN-proof ({@code BigDecimal.valueOf(NaN)}
 * throws, and the {@code ck_assistant_messages_confidence} CHECK rejects anything outside [0,1]).
 * Nothing is ever built inline at a call site; every new field goes through here or the bound is gone.
 */
public final class InterpretationSnapshots {

    static final int MAX_NAME_LENGTH = 200;
    static final int MAX_DESCRIPTION_LENGTH = 2000;
    static final int MAX_LOCATIONS = 10;
    static final int MAX_KEYWORDS = 5;
    static final int MAX_OFFERED_SPACES = 20;
    /** Matches the validator's own cap; a row cannot hold more picks than an answer can show. */
    static final int MAX_MATCHES = 10;
    static final int CONFIDENCE_SCALE = 3;

    private InterpretationSnapshots() {
    }

    /** The model's answer verbatim (capped), with no request context — used as the nested {@code raw}. */
    public static InterpretationSnapshot fromRaw(PlacementInterpretation raw) {
        return fromRaw(raw, null);
    }

    /**
     * The model's answer verbatim (capped), for a REMEMBER request the validator rejected.
     *
     * @param offeredSpaces the space names handed to the model for this request; null for a nested
     *                      snapshot, where the outer row already carries them
     */
    public static InterpretationSnapshot fromRaw(PlacementInterpretation raw, List<String> offeredSpaces) {
        if (raw == null) {
            return new InterpretationSnapshot(false, null, null, null, List.of(), null, null, null, null, null,
                    null, null, null, offered(offeredSpaces), null);
        }
        List<InterpretationSnapshot.Segment> locations = raw.locations() == null ? List.of() : raw.locations().stream()
                .limit(MAX_LOCATIONS)
                .map(segment -> segment == null
                        ? new InterpretationSnapshot.Segment(null, null)
                        : new InterpretationSnapshot.Segment(cap(segment.name(), MAX_NAME_LENGTH),
                                cap(segment.type(), MAX_NAME_LENGTH)))
                .toList();
        return new InterpretationSnapshot(false,
                cap(raw.itemName(), MAX_NAME_LENGTH),
                cap(raw.itemDescription(), MAX_DESCRIPTION_LENGTH),
                cap(raw.spaceName(), MAX_NAME_LENGTH),
                locations,
                confidenceOf(raw.confidence()),
                rawConfidenceOf(raw.confidence()),
                null, null, null, null, null, null,
                offered(offeredSpaces), null);
    }

    /**
     * The cleaned, type-parsed placement that passed the validator (already within its own caps),
     * WITH the model's pre-validation answer nested under {@code raw}.
     *
     * <p>Both halves are kept on purpose. The validator silently degrades an unsafe {@code spaceName}
     * to null and cleans names, so a row carrying only the validated view can read as "the model named
     * no space" when it named an unusable one — the opposite diagnosis.
     *
     * @param raw           the provider's output for this same answer; supplies the confidence, which
     *                      {@link ValidatedPlacement} does not carry, and the nested snapshot
     * @param offeredSpaces the space names handed to the model for this request
     */
    public static InterpretationSnapshot fromValidated(ValidatedPlacement placement,
            PlacementInterpretation raw, List<String> offeredSpaces) {
        List<InterpretationSnapshot.Segment> locations = placement.segments().stream()
                .map(InterpretationSnapshots::toSegment)
                .toList();
        Double confidence = raw == null ? null : raw.confidence();
        return new InterpretationSnapshot(true, placement.itemName(), placement.description(),
                placement.spaceName(), locations, confidenceOf(confidence), rawConfidenceOf(confidence),
                null, null, null, null, null, null,
                offered(offeredSpaces), raw == null ? null : fromRaw(raw));
    }

    /** A provider failure: no answer to snapshot, but what the model was offered is still worth keeping. */
    public static InterpretationSnapshot forFailure(List<String> offeredSpaces) {
        List<String> offered = offered(offeredSpaces);
        return offered == null ? null : new InterpretationSnapshot(null, null, null, null, null, null, null,
                null, null, null, null, null, null, offered, null);
    }

    /**
     * A SEARCH that ran: what it ran with, and what it was given to run with.
     *
     * <p>{@code offeredItemCount} is stored as a COUNT and the names are not. See
     * {@link InterpretationSnapshot} for why this breaks the symmetry with {@code offeredSpaces}
     * on purpose. Zero becomes null so the key disappears from the jsonb entirely rather than
     * every pre-feature-style row carrying a meaningless {@code "offeredItemCount": 0}.
     */
    public static InterpretationSnapshot forSearch(List<String> keywords, List<String> matches,
            int offeredItemCount, boolean usedFallback) {
        return new InterpretationSnapshot(true, null, null, null, null, null, null,
                capList(keywords, MAX_KEYWORDS, MAX_NAME_LENGTH), usedFallback, null, null,
                picked(matches),
                offeredItemCount <= 0 ? null : offeredItemCount, null, null);
    }

    /**
     * A SEARCH the database answered on its own, with no model call.
     *
     * <p>{@code usedFallback} is deliberately FALSE rather than true: nothing fell back, because
     * nothing was asked. {@code offeredItemCount} is absent for the same reason — no list was
     * built, so no item name left the server for this request.
     */
    public static InterpretationSnapshot forSearchWithoutAi(String keyword) {
        return new InterpretationSnapshot(true, null, null, null, null, null, null,
                capList(List.of(keyword), MAX_KEYWORDS, MAX_NAME_LENGTH), false, true,
                null, null, null, null, null);
    }

    /** A SEARCH with nothing usable — keeps what the model returned so the rejection is inspectable. */
    public static InterpretationSnapshot forSearchNotUnderstood(List<String> rawKeywords) {
        return new InterpretationSnapshot(false, null, null, null, null, null, null, List.of(), null, null,
                capList(rawKeywords, MAX_KEYWORDS, MAX_NAME_LENGTH), null, null, null, null);
    }

    /**
     * Normalizes a provider confidence for the {@code numeric(4,3)} column: three decimals so rows are
     * comparable, and NULL for anything that is not a finite number in [0,1]. Written as
     * {@code x >= 0 && x <= 1} so NaN fails the comparison (§6).
     */
    public static BigDecimal confidenceOf(Double confidence) {
        if (confidence == null || !(confidence >= 0.0 && confidence <= 1.0)) {
            return null;
        }
        return BigDecimal.valueOf(confidence).setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP);
    }

    /** The provider's number as text ("0.92", "NaN", "-1.0"), or null — see {@link InterpretationSnapshot#rawConfidence}. */
    static String rawConfidenceOf(Double confidence) {
        return confidence == null ? null : String.valueOf(confidence);
    }

    /** Null (not an empty list) when there is nothing to record, so NON_NULL keeps the key out. */
    /**
     * Null rather than an empty list, so the key disappears from the jsonb entirely.
     *
     * <p>Most searches pick nothing — every keyword-only query, every account under a provider
     * that does not implement the field — and {@code "matches": []} on all of them is noise that
     * reads like a fact. Absent says what is true: the model made no pick. Same reasoning as
     * {@link #offered}, and as the zero-means-absent rule on {@code offeredItemCount}.
     */
    private static List<String> picked(List<String> matches) {
        List<String> capped = capList(matches, MAX_MATCHES, MAX_NAME_LENGTH);
        return capped == null || capped.isEmpty() ? null : capped;
    }

    private static List<String> offered(List<String> offeredSpaces) {
        if (offeredSpaces == null || offeredSpaces.isEmpty()) {
            return null;
        }
        return capList(offeredSpaces, MAX_OFFERED_SPACES, MAX_NAME_LENGTH);
    }

    private static InterpretationSnapshot.Segment toSegment(ChainSegment segment) {
        return new InterpretationSnapshot.Segment(segment.name(), segment.type().name());
    }

    private static List<String> capList(List<String> values, int maxEntries, int maxLength) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .limit(maxEntries)
                .map(value -> cap(value, maxLength))
                .toList();
    }

    private static String cap(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String stripped = value.replaceAll("\\p{Cntrl}", "");
        return stripped.length() <= maxLength ? stripped : stripped.substring(0, maxLength);
    }
}
