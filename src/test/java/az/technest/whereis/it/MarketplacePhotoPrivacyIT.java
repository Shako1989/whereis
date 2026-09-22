package az.technest.whereis.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import az.technest.whereis.item.dto.CreateItemRequest;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.location.LocationType;
import az.technest.whereis.marketplace.seller.dto.ListingRequest;
import az.technest.whereis.marketplace.seller.dto.MyListingResponse;
import az.technest.whereis.space.SpaceType;
import az.technest.whereis.storage.TestImages;
import az.technest.whereis.storage.dto.ItemFileResponse;
import az.technest.whereis.storage.dto.PresignedUrlResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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

/**
 * <strong>The two holes V14 closed, asserted on the bytes an anonymous visitor actually receives.</strong>
 *
 * <p>This is the one guard in the publish path with no type system behind it. Publishing used to be
 * a server-side {@code copyObject}, which is byte-identical by definition, so a listing photo
 * carried the camera's {@code GPSLatitude}, {@code GPSLongitude}, capture time and device identity —
 * defeating the decision that a listing exposes a CITY and never where the item is kept. A test
 * that checked a flag or a code path would pass just as happily against a strip that did nothing,
 * so every assertion here fetches the published object over HTTP and looks in the bytes.
 *
 * <p>The second hole was the destination: the copy went to the PRIVATE bucket and therefore had to
 * be presigned, so every board image URL expired after {@code minio.presign-ttl} (10 minutes,
 * shared with every private photo) and could be cached by nobody.
 */
class MarketplacePhotoPrivacyIT extends AbstractIntegrationTest {

    private static final String BOARD = "/api/v1/market/listings";

    private final HttpClient http = HttpClient.newHttpClient();

    // ---------------------------------------------------------------- the bytes

    @Test
    void thePublishedJpegCarriesNoExifNoGpsAndNoDeviceIdentity() throws Exception {
        Published published = publish("image/jpeg", "photo.jpg", TestImages.jpegWithGpsExif());

        byte[] fetched = fetchOk(published.imageUrl());

        assertThat(TestImages.contains(fetched, TestImages.CANARY))
                .as("the device identity the camera wrote").isFalse();
        assertThat(TestImages.contains(fetched, "Exif")).as("the EXIF block header").isFalse();
        // 40 degrees latitude as a little-endian EXIF rational — the actual coordinate, not a name.
        assertThat(TestImages.contains(fetched, new byte[]{0x28, 0, 0, 0, 1, 0, 0, 0}))
                .as("the GPS latitude rational").isFalse();
        assertThat(TestImages.jpegMarkers(fetched))
                .as("no APPn and no COM segment survives")
                .noneMatch(marker -> (marker >= 0xE0 && marker <= 0xEF) || marker == 0xFE);
    }

    @Test
    void thePublishedJpegsCompressedScanIsIdenticalSoTheImageWasNotReEncoded() throws Exception {
        byte[] original = TestImages.jpegWithGpsExif();
        Published published = publish("image/jpeg", "photo.jpg", original);

        byte[] fetched = fetchOk(published.imageUrl());

        assertThat(TestImages.jpegScan(fetched))
                .as("the container was rewritten; the pixels were copied")
                .isEqualTo(TestImages.jpegScan(original));
    }

    @Test
    void thePublishedPngCarriesNoExifChunkAndNoTextChunk() throws Exception {
        // PNG hides metadata somewhere else entirely — eXIf and the three text chunks — so a strip
        // that only understood JPEG would be a hole here with nothing failing.
        Published published = publish("image/png", "photo.png", TestImages.pngWithGpsExif());

        byte[] fetched = fetchOk(published.imageUrl());

        assertThat(TestImages.pngChunkTypes(fetched))
                .doesNotContain("eXIf", "tEXt", "zTXt", "iTXt", "tIME")
                .contains("IHDR", "IDAT", "IEND");
        assertThat(TestImages.contains(fetched, TestImages.CANARY)).isFalse();
    }

    @Test
    void thePublishedWebpCarriesNoExifChunkAndNoXmpChunk() throws Exception {
        // And WebP hides it somewhere else again — RIFF EXIF and XMP chunks, plus feature bits in
        // VP8X that have to stop advertising what is gone.
        Published published = publish("image/webp", "photo.webp", TestImages.webpWithGpsExif());

        byte[] fetched = fetchOk(published.imageUrl());

        assertThat(TestImages.webpChunkIds(fetched)).doesNotContain("EXIF", "XMP ", "ICCP");
        assertThat(TestImages.contains(fetched, TestImages.CANARY)).isFalse();
        assertThat(TestImages.webpChunk(fetched, "VP8X")[0] & (0x20 | 0x08 | 0x04))
                .as("the ICCP, EXIF and XMP feature bits").isZero();
        assertThat(TestImages.webpChunk(fetched, "VP8L"))
                .as("the image bitstream is untouched").isEqualTo(TestImages.webpImagePayload());
    }

    @Test
    void theOwnersOwnPrivateCopyKeepsItsMetadataExactlyAsUploaded() throws Exception {
        // The strip is a property of PUBLISHING, not of storing. The owner uploaded their file and
        // it is theirs: rewriting it would destroy the capture date and the camera information in
        // the one copy where they are the person the data is about.
        byte[] original = TestImages.jpegWithGpsExif();
        Published published = publish("image/jpeg", "photo.jpg", original);

        PresignedUrlResponse presigned = get(published.token(),
                "/api/v1/items/" + published.itemId() + "/files/" + published.fileId() + "/url",
                PresignedUrlResponse.class).getBody();

        assertThat(fetchOk(presigned.url())).isEqualTo(original);
    }

    // ---------------------------------------------------------------- the URL

    @Test
    void thePublicPhotoUrlIsAPlainPermanentAddressWithNoSignatureAtAll() {
        Published published = publish("image/jpeg", "photo.jpg", TestImages.jpegWithGpsExif());

        assertThat(published.imageUrl())
                .contains("/" + PUBLIC_BUCKET + "/p/")
                .doesNotContain("X-Amz-Signature")
                .doesNotContain("X-Amz-Credential")
                .doesNotContain("X-Amz-Expires")
                .doesNotContain("?");
        // And it is STABLE across reads: a presigned URL is re-signed with the current second, so
        // two board reads used to disagree — which is precisely why nothing could cache it.
        assertThat(imageUrlOf(published.listingId())).isEqualTo(published.imageUrl());
        // It also still leaks neither the seller nor the item, which the opaque p/ key is for.
        assertThat(published.imageUrl())
                .doesNotContain(published.userId().toString())
                .doesNotContain(published.itemId().toString());
    }

    @Test
    void thePublishedObjectTellsBrowsersAndCdnsItMayBeCached() throws Exception {
        Published published = publish("image/jpeg", "photo.jpg", TestImages.jpegWithGpsExif());

        HttpResponse<byte[]> response = fetch(published.imageUrl());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control"))
                .as("set on the object at PUT time, so no reverse proxy has to know this path")
                .hasValue("public, max-age=86400");
    }

    @Test
    void theStoredContentTypeSurvivesSoTheBrowserRendersItAsAnImage() throws Exception {
        Published published = publish("image/png", "photo.png", TestImages.pngWithGpsExif());

        assertThat(fetch(published.imageUrl()).headers().firstValue("Content-Type"))
                .hasValue("image/png");
    }

    @Test
    void thePublicBucketCannotBeLISTEDByAnAnonymousVisitor() throws Exception {
        // The keys are opaque so one photo cannot be tied to a seller. An anonymous ListBucket
        // would hand over every key in one request and the opacity would buy nothing, so the
        // bucket policy grants s3:GetObject and nothing else (deploy/README.md Step 4b).
        Published published = publish("image/jpeg", "photo.jpg", TestImages.jpegWithGpsExif());
        String bucketRoot = published.imageUrl().substring(0,
                published.imageUrl().indexOf("/p/") + 1);

        HttpResponse<byte[]> listing = fetch(bucketRoot);

        assertThat(listing.statusCode()).as("anonymous bucket listing").isEqualTo(403);
    }

    // ---------------------------------------------------------------- revocation

    @Test
    void withdrawingTheListingTakesThePhotoOffTheInternetRatherThanWaitingForATtl() {
        Published published = publish("image/jpeg", "photo.jpg", TestImages.jpegWithGpsExif());
        assertThat(fetch(published.imageUrl()).statusCode()).isEqualTo(200);

        ResponseEntity<JsonNode> withdrawn = rest.exchange("/api/v1/listings/" + published.listingId(),
                HttpMethod.DELETE, new HttpEntity<>(bearer(published.token())), JsonNode.class);

        assertThat(withdrawn.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The URL is permanent, so DELETING the object is the whole of the revocation — there is no
        // expiring signature doing half the work any more.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .until(() -> fetch(published.imageUrl()).statusCode() == 404);
        assertThat(jdbc.queryForObject("select count(*) from item_files where id = ? "
                        + "and published_object_key is null and published_bucket is null",
                Integer.class, published.fileId())).isEqualTo(1);
    }

    @Test
    void deletingTheAccountEnqueuesThePublishedCopyAgainstThePUBLICBucketSoTheJanitorFindsIt() {
        // The half of a two-bucket world that could have silently not worked. Every outbox CONSUMER
        // already honoured a per-row bucket; the PRODUCERS selected the private one. Enqueueing the
        // right key against the wrong bucket has the janitor delete nothing, report success, drop
        // the row, and leave the photo on the open internet for good.
        Published published = publish("image/jpeg", "photo.jpg", TestImages.jpegWithGpsExif());
        String publishedKey = jdbc.queryForObject(
                "select published_object_key from item_files where id = ?", String.class,
                published.fileId());

        ResponseEntity<JsonNode> deleted = deleteWithBody(published.token(), "/api/v1/users/me",
                new az.technest.whereis.user.dto.DeleteAccountRequest(PASSWORD), JsonNode.class);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbc.queryForObject("select bucket from storage_deletion_queue where object_key = ?",
                String.class, publishedKey)).isEqualTo(PUBLIC_BUCKET);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .until(() -> fetch(published.imageUrl()).statusCode() == 404);
    }

    // ---------------------------------------------------------------- refusals and degradation

    @Test
    void aPhotoWhoseContainerCannotBeRewrittenREFUSESThePublishInsteadOfLeakingTheOriginal() {
        // These twelve bytes pass the magic-byte check and are what AbstractIntegrationTest's
        // fixture used to be: an APP0 segment declaring a length past the end of the file. Nothing
        // can prove such a file's metadata is gone, so the publish is refused — the alternative
        // being a fallback to the original bytes, which is this whole hole reopening quietly.
        byte[] unparseable = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                (byte) 0xFF, (byte) 0xFF, 1, 2, 3, 4, 5, 6};
        Seller seller = newSeller();
        UUID itemId = newItemWithPhoto(seller, "Qirilmis foto", "image/jpeg", "odd.jpg", unparseable)
                .itemId();

        ResponseEntity<JsonNode> refused = post(seller.token(), "/api/v1/items/" + itemId + "/listing",
                listingRequest("Qirilmis foto", "BAKU"), JsonNode.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().get("code").asText()).isEqualTo("LISTING_PHOTO_UNPUBLISHABLE");
        // Nothing partial: no listing row, and no published copy recorded.
        assertThat(jdbc.queryForObject("select count(*) from listings where item_id = ?",
                Integer.class, itemId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from item_files where item_id = ? "
                + "and published_object_key is not null", Integer.class, itemId)).isZero();
    }

    @Test
    void aListingWithNoPublishedCopyIsStillServedByTheBoardJustWithoutAnImage() {
        // Two states reach here and both must DEGRADE rather than fail: a deployment whose public
        // bucket is not configured yet, and a listing published before V14 moved published copies
        // out of the private bucket (that migration unpublishes them, deliberately). Simulated in
        // SQL because changing minio.public-bucket would fork the shared Spring context.
        Published published = publish("image/jpeg", "photo.jpg", TestImages.jpegWithGpsExif());
        jdbc.update("update item_files set published_object_key = null, published_bucket = null "
                + "where id = ?", published.fileId());

        JsonNode detail = rest.getForEntity(BOARD + "/" + published.listingId(), JsonNode.class)
                .getBody();

        assertThat(detail.get("title").asText()).isNotBlank();
        assertThat(detail.get("imageUrl").isNull()).as("no image, and no failure").isTrue();
        assertThat(rest.getForEntity(BOARD + "/" + published.listingId(), JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void thePublishedCopyIsNotInThePrivateBucketAndTheOriginalIsNotInThePublicOne() {
        Published published = publish("image/jpeg", "photo.jpg", TestImages.jpegWithGpsExif());

        assertThat(jdbc.queryForMap("select bucket, published_bucket from item_files where id = ?",
                published.fileId()))
                .containsEntry("bucket", minioBucketName())
                .containsEntry("published_bucket", PUBLIC_BUCKET);
    }

    // ---------------------------------------------------------------- helpers

    private record Seller(String token, UUID userId, UUID locationId) {
    }

    private record Published(String token, UUID userId, UUID itemId, UUID fileId, UUID listingId,
                             String imageUrl) {
    }

    private record ItemWithPhoto(UUID itemId, UUID fileId) {
    }

    private String minioBucketName() {
        return jdbc.queryForObject("select bucket from item_files limit 1", String.class);
    }

    private Seller newSeller() {
        String token = registerAndGetToken();
        UUID userId = subjectOf(token);
        UUID spaceId = createSpace(token, "Ev", SpaceType.HOME).id();
        UUID shelfId = createLocation(token, spaceId, "Skaf", LocationType.FURNITURE, null).id();
        return new Seller(token, userId, shelfId);
    }

    private ItemWithPhoto newItemWithPhoto(Seller seller, String name, String contentType,
            String filename, byte[] bytes) {
        UUID itemId = post(seller.token(), "/api/v1/items",
                new CreateItemRequest(name, null, null, seller.locationId()), ItemResponse.class)
                .getBody().id();
        return new ItemWithPhoto(itemId, upload(seller.token(), itemId, contentType, filename, bytes));
    }

    /** Registers a seller, uploads the given image, publishes it, and reads the board's URL back. */
    private Published publish(String contentType, String filename, byte[] bytes) {
        Seller seller = newSeller();
        String title = "Kamera " + UUID.randomUUID().toString().substring(0, 8);
        ItemWithPhoto photo = newItemWithPhoto(seller, title, contentType, filename, bytes);

        ResponseEntity<MyListingResponse> created = post(seller.token(),
                "/api/v1/items/" + photo.itemId() + "/listing", listingRequest(title, "BAKU"),
                MyListingResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID listingId = created.getBody().id();
        return new Published(seller.token(), seller.userId(), photo.itemId(), photo.fileId(),
                listingId, imageUrlOf(listingId));
    }

    private String imageUrlOf(UUID listingId) {
        JsonNode detail = rest.getForEntity(BOARD + "/" + listingId, JsonNode.class).getBody();
        assertThat(detail.get("imageUrl").isNull()).as("a published listing has an image").isFalse();
        return detail.get("imageUrl").asText();
    }

    private UUID upload(String token, UUID itemId, String contentType, String filename, byte[] bytes) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.parseMediaType(contentType));
        body.add("file", new HttpEntity<>(new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, partHeaders));
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<ItemFileResponse> uploaded = rest.exchange(
                "/api/v1/items/" + itemId + "/files?primary=true", HttpMethod.POST,
                new HttpEntity<>(body, headers), ItemFileResponse.class);
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return uploaded.getBody().id();
    }

    private static ListingRequest listingRequest(String title, String city) {
        return new ListingRequest(title, "Az islenmis, tam saz veziyyetde, qutusu ile birlikde.",
                new BigDecimal("250.00"), "+994 50 123 45 67", city, null);
    }

    private byte[] fetchOk(String url) {
        HttpResponse<byte[]> response = fetch(url);
        assertThat(response.statusCode()).as("GET %s", url).isEqualTo(200);
        return response.body();
    }

    private HttpResponse<byte[]> fetch(String url) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
