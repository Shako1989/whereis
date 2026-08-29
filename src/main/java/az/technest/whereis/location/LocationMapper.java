package az.technest.whereis.location;

import az.technest.whereis.location.dto.LocationResponse;
import org.mapstruct.Mapper;

@Mapper
public interface LocationMapper {

    LocationResponse toResponse(Location location);
}
