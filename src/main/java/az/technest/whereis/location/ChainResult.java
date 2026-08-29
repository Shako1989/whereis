package az.technest.whereis.location;

import java.util.List;

/** Result of resolving/creating a location chain: the leaf plus the names that had to be created. */
public record ChainResult(Location leaf, List<String> createdNames) {
}
