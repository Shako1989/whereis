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
        if (!isSafeName(itemName, 120)) {
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
