package az.technest.whereis.item;

import az.technest.whereis.item.dto.ItemHistoryResponse;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.storage.dto.ItemPrimaryImage;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface ItemMapper {

    /** {@code primaryImage} is null for items with no cover photo: both fields come back null. */
    @Mapping(target = "locationPath", source = "locationPath")
    @Mapping(target = "primaryFileId", source = "primaryImage.fileId")
    @Mapping(target = "primaryImageUrl", source = "primaryImage.url")
    ItemResponse toResponse(Item item, List<String> locationPath, ItemPrimaryImage primaryImage);

    @Mapping(target = "locationPath", source = "locationPathSnapshot")
    ItemHistoryResponse toHistoryResponse(ItemLocationHistory history);
}
