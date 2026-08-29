package az.technest.whereis.location.dto;

import az.technest.whereis.location.LocationType;
import java.util.List;
import java.util.UUID;

public record LocationTreeNode(
        UUID id,
        String name,
        LocationType type,
        List<LocationTreeNode> children
) {
}
