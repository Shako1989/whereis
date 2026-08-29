package az.technest.whereis.assistant;

import java.util.List;

/** Raw, untrusted AI search interpretation — keywords only, never answers. */
public record SearchInterpretation(List<String> keywords) {
}
