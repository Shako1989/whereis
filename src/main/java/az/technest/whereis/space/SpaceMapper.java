package az.technest.whereis.space;

import az.technest.whereis.space.dto.SpaceResponse;
import org.mapstruct.Mapper;

@Mapper
public interface SpaceMapper {

    SpaceResponse toResponse(Space space);
}
