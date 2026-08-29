package az.technest.whereis.assistant;

/**
 * Provider-agnostic AI port. Implementations understand natural language;
 * they hold no business logic and have no data access. All output is untrusted
 * until validated, and the database remains the only source of truth.
 */
public interface AiAssistant {

    PlacementInterpretation interpretPlacement(String message);

    SearchInterpretation interpretSearch(String message);

    ImageAnalysis analyzeImage(byte[] content, String contentType);
}
