package az.technest.whereis.item;

import az.technest.whereis.item.dto.ItemHistoryResponse;
import az.technest.whereis.item.dto.ItemResponse;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface ItemMapper {

    @Mapping(target = "locationPath", source = "locationPath")
    ItemResponse toResponse(Item item, List<String> locationPath);

    @Mapping(target = "locationPath", source = "locationPathSnapshot")
    ItemHistoryResponse toHistoryResponse(ItemLocationHistory history);
}
