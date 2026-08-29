package az.technest.whereis.assistant.mock;

import az.technest.whereis.assistant.AiAssistant;
import az.technest.whereis.assistant.ImageAnalysis;
import az.technest.whereis.assistant.LocationSegment;
import az.technest.whereis.assistant.PlacementInterpretation;
import az.technest.whereis.assistant.SearchInterpretation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, rule-based interpreter. Default provider for local development and
 * tests: no network, no key, same JSON contract as the real provider. Understands
 * sentences like "I put my passport in the bedroom wardrobe top drawer".
 */
public class MockAiAssistant implements AiAssistant {

    private static final Pattern PLACEMENT = Pattern.compile(
            "\\bi\\s+(?:put|placed|left|stored|keep|kept|moved)\\s+(?:my|the|a|an)?\\s*(?<item>.+?)\\s+"
                    + "(?:in|into|inside|on|at|to)\\s+(?<place>.+)$",
            Pattern.CASE_INSENSITIVE);

    /** Space phrases stripped from the location part; value is the space name to report. */
    private static final Map<String, String> SPACE_PHRASES = orderedMap(
            "at home", "Home",
            "at the office", "Office",
            "at work", "Office",
            "in the car", "Car",
            "in my car", "Car",
            "in the garage", "Garage",
            "at the garage", "Garage",
            "at the warehouse", "Warehouse",
            "in the warehouse", "Warehouse");

    /** Location keywords (longest first for greedy matching) and their types. */
    private static final Map<String, String> KEYWORDS = orderedMap(
            "living room", "ROOM",
            "dining room", "ROOM",
            "bookshelf", "SHELF",
            "bedroom", "ROOM",
            "kitchen", "ROOM",
            "bathroom", "ROOM",
            "hallway", "ROOM",
            "balcony", "ROOM",
            "office", "ROOM",
            "garage", "ROOM",
            "room", "ROOM",
            "wardrobe", "FURNITURE",
            "closet", "FURNITURE",
            "dresser", "FURNITURE",
            "table", "FURNITURE",
            "nightstand", "FURNITURE",
            "cupboard", "CABINET",
            "cabinet", "CABINET",
            "drawer", "DRAWER",
            "shelf", "SHELF",
            "box", "BOX",
            "desk", "DESK",
            "bag", "BAG",
            "backpack", "BAG",
            "suitcase", "BAG",
            "container", "CONTAINER",
            "basket", "CONTAINER",
            "bin", "CONTAINER");

    private static final Set<String> FILLER_WORDS = Set.of("the", "a", "an", "my", "of", "our", "his", "her");

    private static final Set<String> SEARCH_STOPWORDS = Set.of(
            "where", "is", "are", "was", "were", "my", "the", "a", "an", "did", "i", "put", "place",
            "placed", "leave", "left", "store", "stored", "find", "me", "of", "in", "to", "what",
            "which", "do", "you", "know", "can", "please", "things", "stuff", "all");

    @Override
    public PlacementInterpretation interpretPlacement(String message) {
        String normalized = message == null ? "" : message.trim().replaceAll("\\s+", " ");
        Matcher matcher = PLACEMENT.matcher(stripTrailingPunctuation(normalized));
        if (!matcher.find()) {
            return new PlacementInterpretation(null, null, null, List.of(), 0.0);
        }
        String item = titleCase(stripFillers(matcher.group("item")));
        String place = matcher.group("place").toLowerCase(Locale.ROOT);

        String spaceName = null;
        for (Map.Entry<String, String> entry : SPACE_PHRASES.entrySet()) {
            if (place.contains(entry.getKey())) {
                spaceName = entry.getValue();
                place = place.replace(entry.getKey(), " ");
                break;
            }
        }
        List<LocationSegment> segments = segmentize(place);
        if (item.isBlank() || segments.isEmpty()) {
            return new PlacementInterpretation(null, null, null, List.of(), 0.0);
        }
        return new PlacementInterpretation(item, null, spaceName, segments, 0.9);
    }

    @Override
    public SearchInterpretation interpretSearch(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ");
        List<String> keywords = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2 && !SEARCH_STOPWORDS.contains(token) && !keywords.contains(token)) {
                keywords.add(token);
            }
            if (keywords.size() == 5) {
                break;
            }
        }
        return new SearchInterpretation(keywords);
    }

    @Override
    public ImageAnalysis analyzeImage(byte[] content, String contentType) {
        // Canned deterministic result — real detection arrives with a vision-capable provider.
        return new ImageAnalysis(List.of(
                new ImageAnalysis.ItemSuggestion("USB-C Cable", "Electronics"),
                new ImageAnalysis.ItemSuggestion("HDMI Cable", "Electronics"),
                new ImageAnalysis.ItemSuggestion("AA Batteries", "Electronics"),
                new ImageAnalysis.ItemSuggestion("Phone Charger", "Electronics")));
    }

    /**
     * Splits a phrase like "bedroom wardrobe top drawer" into typed segments:
     * a segment ends at each known keyword; non-keyword words become modifiers
     * of the next keyword ("top drawer"); trailing words form an OTHER segment.
     */
    private static List<LocationSegment> segmentize(String place) {
        List<String> tokens = new ArrayList<>();
        for (String token : place.split("[\\s,]+")) {
            String cleaned = token.trim();
            if (!cleaned.isEmpty() && !FILLER_WORDS.contains(cleaned)) {
                tokens.add(cleaned);
            }
        }
        List<LocationSegment> segments = new ArrayList<>();
        List<String> buffer = new ArrayList<>();
        for (int i = 0; i < tokens.size(); ) {
            String pair = i + 1 < tokens.size() ? tokens.get(i) + " " + tokens.get(i + 1) : null;
            if (pair != null && KEYWORDS.containsKey(pair)) {
                buffer.add(pair);
                segments.add(segment(buffer, KEYWORDS.get(pair)));
                buffer.clear();
                i += 2;
            } else if (KEYWORDS.containsKey(tokens.get(i))) {
                buffer.add(tokens.get(i));
                segments.add(segment(buffer, KEYWORDS.get(tokens.get(i))));
                buffer.clear();
                i++;
            } else {
                buffer.add(tokens.get(i));
                i++;
            }
        }
        if (!buffer.isEmpty()) {
            segments.add(segment(buffer, "OTHER"));
        }
        return segments;
    }

    private static LocationSegment segment(List<String> words, String type) {
        return new LocationSegment(titleCase(String.join(" ", words)), type);
    }

    private static String stripFillers(String phrase) {
        List<String> kept = new ArrayList<>();
        for (String token : phrase.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!FILLER_WORDS.contains(token)) {
                kept.add(token);
            }
        }
        return String.join(" ", kept);
    }

    private static String stripTrailingPunctuation(String value) {
        return value.replaceAll("[.!?\\s]+$", "");
    }

    private static String titleCase(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (String word : value.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static Map<String, String> orderedMap(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
