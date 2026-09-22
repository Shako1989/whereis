package az.technest.whereis.assistant;

import az.technest.whereis.common.util.Names;
import az.technest.whereis.location.ChainSegment;
import az.technest.whereis.location.LocationType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The trust boundary between AI output and the application. Anything that fails here
 * is treated as "not understood" — never persisted, never partially applied.
 */
@Component
public class InterpretationValidator {

    static final int MAX_CHAIN_DEPTH = 6;
    static final double MIN_CONFIDENCE = 0.6;
    static final int MAX_ITEM_NAME_LENGTH = 120;
    /** Equal to {@code AssistantService.MAX_ANSWER_ITEMS} — an answer cannot show more. */
    static final int MAX_MATCHES = 10;
    private static final Pattern SAFE_NAME = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} .,'&()\\-]*$");

    public record ValidatedPlacement(
            String itemName,
            String description,
            String spaceName,
            List<ChainSegment> segments
    ) {
    }

    public Optional<ValidatedPlacement> validatePlacement(PlacementInterpretation interpretation) {
        if (interpretation == null || !isPlausibleConfidence(interpretation.confidence())) {
            return Optional.empty();
        }
        String itemName = Names.clean(interpretation.itemName());
        if (!isSafeName(itemName, MAX_ITEM_NAME_LENGTH)) {
            return Optional.empty();
        }
        List<LocationSegment> locations = interpretation.locations();
        if (locations == null || locations.isEmpty() || locations.size() > MAX_CHAIN_DEPTH) {
            return Optional.empty();
        }
        List<ChainSegment> segments = new ArrayList<>(locations.size());
        for (LocationSegment segment : locations) {
            String name = Names.clean(segment == null ? null : segment.name());
            if (!isSafeName(name, 80)) {
                return Optional.empty();
            }
            segments.add(new ChainSegment(name, parseType(segment.type())));
        }
        // An unusable space name degrades to "unspecified" — resolution may still succeed
        // via the exactly-one-space rule; it never creates a space.
        String spaceName = Names.clean(interpretation.spaceName());
        if (!isSafeName(spaceName, 80)) {
            spaceName = null;
        }
        String description = Names.clean(interpretation.itemDescription());
        if (description != null && description.length() > 2000) {
            description = description.substring(0, 2000);
        }
        return Optional.of(new ValidatedPlacement(itemName, description, spaceName, List.copyOf(segments)));
    }

    public List<String> validateKeywords(SearchInterpretation interpretation) {
        if (interpretation == null || interpretation.keywords() == null) {
            return List.of();
        }
        List<String> keywords = new ArrayList<>();
        for (String raw : interpretation.keywords()) {
            String keyword = Names.normalize(raw);
            if (keyword != null && keyword.length() >= 2 && keyword.length() <= 50
                    && SAFE_NAME.matcher(keyword).matches() && !keywords.contains(keyword)) {
                keywords.add(keyword);
            }
            if (keywords.size() == 5) {
                break;
            }
        }
        return keywords;
    }

    /**
     * The item names the model picked out of the list it was shown.
     *
     * <p>Normalized rather than cleaned, unlike {@link #validatePlacement}: these are not going to
     * be displayed, they are going to be LOOKED UP, and the lookup key is {@code normalized_name}.
     * Normalizing here is what makes "Matkap", "matkap" and a stray non-breaking space all find
     * the same row.
     *
     * <p>Nothing here proves the name is the caller's — that is the resolving query's job, and it
     * is scoped by {@code user_id}. What this does prove is that each entry is short enough and
     * plain enough to be a name at all, so a model that returns a sentence, a SQL fragment or a
     * thousand characters gets those entries dropped rather than passed to the database.
     *
     * <p>Capped at {@code MAX_MATCHES}, which equals the number of items an answer can hold:
     * validating more than can ever be shown would be work with no reader.
     */
    public List<String> validateMatches(SearchInterpretation interpretation) {
        if (interpretation == null || interpretation.matches() == null) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String raw : interpretation.matches()) {
            String match = Names.normalize(raw);
            // Length 1 is allowed where a keyword needs 2: a one-character item name is a real
            // name a user can register, and this value is an exact lookup rather than a fuzzy
            // one, so a single letter cannot drag half the inventory back.
            if (match != null && !match.isEmpty() && match.length() <= MAX_ITEM_NAME_LENGTH
                    && SAFE_NAME.matcher(match).matches() && !matches.contains(match)) {
                matches.add(match);
            }
            if (matches.size() == MAX_MATCHES) {
                break;
            }
        }
        return matches;
    }

    /** AI-supplied image suggestions pass the same trust boundary as everything else. */
    public List<ImageAnalysis.ItemSuggestion> validateSuggestions(ImageAnalysis analysis) {
        if (analysis == null || analysis.suggestions() == null) {
            return List.of();
        }
        List<ImageAnalysis.ItemSuggestion> validated = new ArrayList<>();
        for (ImageAnalysis.ItemSuggestion suggestion : analysis.suggestions()) {
            String name = Names.clean(suggestion == null ? null : suggestion.name());
            if (!isSafeName(name, 120)) {
                continue;
            }
            String category = Names.clean(suggestion.category());
            if (category != null && !isSafeName(category, 100)) {
                category = null;
            }
            validated.add(new ImageAnalysis.ItemSuggestion(name, category));
            if (validated.size() == 10) {
                break;
            }
        }
        return List.copyOf(validated);
    }

    /** Written so NaN fails the comparison (NaN >= x is false); >1 is not a valid confidence. */
    private static boolean isPlausibleConfidence(Double confidence) {
        return confidence != null && confidence >= MIN_CONFIDENCE && confidence <= 1.0;
    }

    /**
     * The item-name rule on its own. The pinned path (BR-7) has no model output to validate — the
     * user's text IS the name — but it still has to survive the same charset and length limits as a
     * name the model produced, because those limits are about what the database and the UI can hold,
     * not about trusting the provider.
     */
    public boolean isUsableItemName(String name) {
        return isSafeName(name, MAX_ITEM_NAME_LENGTH);
    }

    private static boolean isSafeName(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength
                && SAFE_NAME.matcher(value).matches();
    }

    private static LocationType parseType(String raw) {
        if (raw == null) {
            return LocationType.OTHER;
        }
        try {
            return LocationType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return LocationType.OTHER;
        }
    }
}
