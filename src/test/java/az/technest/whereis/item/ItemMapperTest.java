package az.technest.whereis.item;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.storage.dto.ItemPrimaryImage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ItemMapperTest {

    private final ItemMapper mapper = new ItemMapperImpl();

    private Item item() {
        return Item.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .currentLocationId(UUID.randomUUID())
                .name("Passport")
                .normalizedName("passport")
                .archived(false)
                .build();
    }

    @Test
    void fillsBothCoverPhotoFieldsFromThePrimaryImage() {
        UUID fileId = UUID.randomUUID();

        ItemResponse response = mapper.toResponse(item(), List.of("Home", "Bedroom"),
                new ItemPrimaryImage(fileId, "https://minio/presigned"));

        // The id is returned alongside the URL on purpose: presigned URLs rotate, the id does not,
        // so clients can keep caching images across pages.
        assertThat(response.primaryFileId()).isEqualTo(fileId);
        assertThat(response.primaryImageUrl()).isEqualTo("https://minio/presigned");
        assertThat(response.locationPath()).containsExactly("Home", "Bedroom");
    }

    @Test
    void itemWithoutAPhotoYieldsNullsForBothCoverFields() {
        ItemResponse response = mapper.toResponse(item(), List.of("Home"), null);

        assertThat(response.primaryFileId()).isNull();
        assertThat(response.primaryImageUrl()).isNull();
        assertThat(response.name()).isEqualTo("Passport");
    }
}
