package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;

import az.technest.whereis.item.dto.CreateItemRequest;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.location.dto.LocationResponse;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.space.dto.SpaceResponse;
import az.technest.whereis.storage.dto.ItemFileResponse;
import az.technest.whereis.storage.dto.PresignedUrlResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class FileStorageIT extends AbstractIntegrationTest {

    private static final byte[] JPEG_BYTES =
            {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 10, 20, 30, 40, 50, 60, 70, 80};

    private UUID newItem(String token) {
        SpaceResponse home = createSpace(token, "Home", SpaceType.HOME);
        LocationResponse drawer = createLocation(token, home.id(), "Drawer", LocationType.DRAWER, null);
        return post(token, "/api/v1/items",
                new CreateItemRequest("Passport", null, null, drawer.id()), ItemResponse.class).getBody().id();
    }

    private ResponseEntity<ItemFileResponse> upload(String token, UUID itemId, String contentType, byte[] bytes) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        body.add("file", new HttpEntity<>(new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "photo.jpg";
            }
        }, partHeaders));
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.exchange("/api/v1/items/" + itemId + "/files?primary=true", HttpMethod.POST,
                new HttpEntity<>(body, headers), ItemFileResponse.class);
    }

    @Test
    void uploadPresignFetchDeleteRoundTrip() throws Exception {
        String token = registerAndGetToken();
        UUID itemId = newItem(token);

        ResponseEntity<ItemFileResponse> uploaded = upload(token, itemId, "image/jpeg", JPEG_BYTES);
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID fileId = uploaded.getBody().id();
        assertThat(uploaded.getBody().isPrimary()).isTrue();

        ResponseEntity<JsonNode> listed = get(token, "/api/v1/items/" + itemId + "/files", JsonNode.class);
        assertThat(listed.getBody()).hasSize(1);

        // The presigned URL must actually be fetchable — this is the only way to catch
        // signing-endpoint/host mismatches.
        PresignedUrlResponse presigned = get(token,
                "/api/v1/items/" + itemId + "/files/" + fileId + "/url", PresignedUrlResponse.class).getBody();
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpResponse<byte[]> fetched = httpClient.send(
                HttpRequest.newBuilder(URI.create(presigned.url())).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(JPEG_BYTES);

        // Delete removes metadata AND the MinIO object (afterCommit sweep).
        ResponseEntity<Void> deleted = rest.exchange("/api/v1/items/" + itemId + "/files/" + fileId,
                HttpMethod.DELETE, new HttpEntity<>(bearer(token)), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        HttpResponse<byte[]> afterDelete = httpClient.send(
                HttpRequest.newBuilder(URI.create(presigned.url())).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(afterDelete.statusCode()).isNotEqualTo(200);

        assertThat(get(token, "/api/v1/items/" + itemId + "/files", JsonNode.class).getBody()).isEmpty();
    }

    @Test
    void contentTypeSpoofingIsRejected() {
        String token = registerAndGetToken();
        UUID itemId = newItem(token);
        byte[] notAJpeg = "<svg onload=alert(1)>".getBytes();

        ResponseEntity<ItemFileResponse> response = upload(token, itemId, "image/jpeg", notAJpeg);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void foreignUsersCannotTouchFiles() {
        String alice = registerAndGetToken();
        String mallory = registerAndGetToken();
        UUID itemId = newItem(alice);
        UUID fileId = upload(alice, itemId, "image/jpeg", JPEG_BYTES).getBody().id();

        assertThat(get(mallory, "/api/v1/items/" + itemId + "/files", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(get(mallory, "/api/v1/items/" + itemId + "/files/" + fileId + "/url", String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rest.exchange("/api/v1/items/" + itemId + "/files/" + fileId, HttpMethod.DELETE,
                new HttpEntity<>(bearer(mallory)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
