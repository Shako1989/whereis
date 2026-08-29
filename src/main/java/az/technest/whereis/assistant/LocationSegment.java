package az.technest.whereis.assistant;

/** One AI-proposed location segment; type is a raw string until validated. */
public record LocationSegment(String name, String type) {
}
