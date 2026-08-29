package az.technest.whereis.location;

/** One segment of a location chain to resolve or create, e.g. ("Bedroom", ROOM). */
public record ChainSegment(String name, LocationType type) {

    public ChainSegment {
        type = type == null ? LocationType.OTHER : type;
    }
}
