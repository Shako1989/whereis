package az.technest.whereis.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;

/**
 * The container rewrite, asserted on BYTES in both directions: the metadata is gone, and the
 * compressed image data is byte-identical. Both halves matter — a "stripper" that returned an empty
 * array would pass the first assertion on its own.
 */
class ImageMetadataStripperTest {

    // ------------------------------------------------------------------ JPEG

    @Test
    void aJpegLosesEveryApplicationSegmentAndItsComment() {
        byte[] original = TestImages.jpegWithGpsExif();
        assertThat(TestImages.jpegMarkers(original)).contains(0xE1, 0xFE);
        assertThat(TestImages.contains(original, TestImages.CANARY)).isTrue();

        byte[] stripped = ImageMetadataStripper.strip(original);

        assertThat(TestImages.jpegMarkers(stripped))
                .as("no APPn (0xE0-0xEF) and no COM (0xFE) survives")
                .noneMatch(marker -> (marker >= 0xE0 && marker <= 0xEF) || marker == 0xFE);
        assertThat(TestImages.contains(stripped, TestImages.CANARY)).isFalse();
        assertThat(TestImages.contains(stripped, "Exif")).isFalse();
    }

    @Test
    void theGpsCoordinateRationalsAreGoneFromTheJpegBytes() {
        // The canary is a string a careless implementation could conceivably re-encode; the GPS
        // rationals are the actual payload, so they are asserted separately. 40 degrees latitude,
        // little-endian, is the four bytes 0x28 0x00 0x00 0x00 followed by the denominator 1.
        byte[] latitude = {0x28, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00};
        byte[] original = TestImages.jpegWithGpsExif();
        assertThat(TestImages.contains(original, latitude)).isTrue();

        assertThat(TestImages.contains(ImageMetadataStripper.strip(original), latitude)).isFalse();
    }

    @Test
    void theJpegsCompressedScanIsCopiedByteForByteSoNothingIsReEncoded() {
        byte[] original = TestImages.jpegWithGpsExif();

        byte[] stripped = ImageMetadataStripper.strip(original);

        assertThat(TestImages.jpegScan(stripped)).isEqualTo(TestImages.jpegScan(original));
        assertThat(stripped.length).isLessThan(original.length);
    }

    @Test
    void theStrippedJpegIsStillADecodableImageOfTheSameSize() throws Exception {
        BufferedImage decoded = ImageIO.read(
                new ByteArrayInputStream(ImageMetadataStripper.strip(TestImages.jpegWithGpsExif())));

        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(4);
        assertThat(decoded.getHeight()).isEqualTo(4);
    }

    @Test
    void aProgressiveJpegsSeveralScansAllSurvive() throws Exception {
        // The scan walk has to find the marker AFTER entropy-coded data without decoding it, and a
        // progressive JPEG has several scans in a row. Getting this wrong corrupts the image rather
        // than the metadata, which is the failure a metadata assertion alone would not notice.
        byte[] original = withExif(progressiveJpeg());
        assertThat(TestImages.jpegMarkers(original)).filteredOn(marker -> marker == 0xDA)
                .as("a progressive JPEG has more than one scan").hasSizeGreaterThan(1);

        byte[] stripped = ImageMetadataStripper.strip(original);

        assertThat(TestImages.contains(stripped, TestImages.CANARY)).isFalse();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(stripped));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(4);
    }

    @Test
    void anythingAppendedAfterTheEndOfImageMarkerIsDropped() {
        // The other place a tool hides metadata: bytes after EOI, which every decoder ignores and
        // every `strings` invocation finds.
        byte[] original = TestImages.jpegWithGpsExif();
        byte[] withTrailer = Arrays.copyOf(original, original.length + 32);
        System.arraycopy(("TRAILER " + TestImages.CANARY).getBytes(StandardCharsets.US_ASCII), 0,
                withTrailer, original.length, 32);

        assertThat(TestImages.contains(ImageMetadataStripper.strip(withTrailer), TestImages.CANARY))
                .isFalse();
    }

    // ------------------------------------------------------------------ PNG

    @Test
    void aPngLosesItsExifAndTextChunksAndKeepsTheCriticalOnes() {
        byte[] original = TestImages.pngWithGpsExif();
        assertThat(TestImages.pngChunkTypes(original)).contains("eXIf", "tEXt");

        byte[] stripped = ImageMetadataStripper.strip(original);

        assertThat(TestImages.pngChunkTypes(stripped))
                .doesNotContain("eXIf", "tEXt", "zTXt", "iTXt", "tIME")
                .contains("IHDR", "IDAT", "IEND");
        assertThat(TestImages.contains(stripped, TestImages.CANARY)).isFalse();
    }

    @Test
    void theStrippedPngIsStillADecodableImageWithTheSamePixels() throws Exception {
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(TestImages.pngWithGpsExif()));

        BufferedImage stripped = ImageIO.read(
                new ByteArrayInputStream(ImageMetadataStripper.strip(TestImages.pngWithGpsExif())));

        assertThat(stripped).isNotNull();
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                assertThat(stripped.getRGB(x, y)).as("pixel %s,%s", x, y)
                        .isEqualTo(original.getRGB(x, y));
            }
        }
    }

    // ------------------------------------------------------------------ WebP

    @Test
    void aWebpLosesItsExifAndXmpChunks() {
        byte[] original = TestImages.webpWithGpsExif();
        assertThat(TestImages.webpChunkIds(original)).contains("EXIF", "XMP ");

        byte[] stripped = ImageMetadataStripper.strip(original);

        assertThat(TestImages.webpChunkIds(stripped))
                .doesNotContain("EXIF", "XMP ", "ICCP")
                .containsExactly("VP8X", "VP8L");
        assertThat(TestImages.contains(stripped, TestImages.CANARY)).isFalse();
    }

    @Test
    void theWebpsVp8xHeaderStopsAdvertisingMetadataThatIsGone() {
        // Dropping the chunks and leaving the feature bits set produces a file whose header promises
        // an EXIF chunk that is not there, which a strict decoder may reject outright.
        assertThat(TestImages.webpChunk(TestImages.webpWithGpsExif(), "VP8X")[0] & 0xFF)
                .isEqualTo(0x08 | 0x04);

        byte[] vp8x = TestImages.webpChunk(
                ImageMetadataStripper.strip(TestImages.webpWithGpsExif()), "VP8X");

        assertThat(vp8x[0] & (0x20 | 0x08 | 0x04)).as("ICCP, EXIF and XMP bits").isZero();
    }

    @Test
    void theWebpsImageBitstreamIsCopiedByteForByteAndTheRiffSizeIsRewritten() {
        byte[] stripped = ImageMetadataStripper.strip(TestImages.webpWithGpsExif());

        assertThat(TestImages.webpChunk(stripped, "VP8L")).isEqualTo(TestImages.webpImagePayload());
        // The RIFF size covers everything after its own 8-byte header; a stale one from the longer
        // file would make every decoder read past the end.
        int declared = (stripped[4] & 0xFF) | ((stripped[5] & 0xFF) << 8)
                | ((stripped[6] & 0xFF) << 16) | ((stripped[7] & 0xFF) << 24);
        assertThat(declared).isEqualTo(stripped.length - 8);
    }

    @Test
    void aSimpleWebpWithNoMetadataAtAllSurvivesIntact() {
        byte[] simple = simpleWebp();

        byte[] stripped = ImageMetadataStripper.strip(simple);

        assertThat(TestImages.webpChunkIds(stripped)).containsExactly("VP8L");
        assertThat(stripped).isEqualTo(simple);
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void strippingTwiceChangesNothingTheSecondTime() {
        byte[] once = ImageMetadataStripper.strip(TestImages.jpegWithGpsExif());

        assertThat(ImageMetadataStripper.strip(once)).isEqualTo(once);
    }

    @Test
    void anUnrecognisedContainerIsRefusedRatherThanPassedThrough() {
        byte[] notAnImage = "GIF89a and then some bytes that are not an image at all"
                .getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> ImageMetadataStripper.strip(notAnImage))
                .isInstanceOf(UnstrippableImageException.class)
                .hasMessageContaining("unrecognised");
    }

    @Test
    void aTruncatedJpegIsRefusedRatherThanPartiallyStripped() {
        byte[] original = TestImages.jpegWithGpsExif();

        assertThatThrownBy(() -> ImageMetadataStripper.strip(Arrays.copyOf(original, 40)))
                .isInstanceOf(UnstrippableImageException.class);
    }

    @Test
    void aPngWithNoEndChunkIsRefused() {
        byte[] original = TestImages.pngWithGpsExif();

        assertThatThrownBy(() -> ImageMetadataStripper.strip(
                Arrays.copyOf(original, original.length - 12)))
                .isInstanceOf(UnstrippableImageException.class)
                .hasMessageContaining("IEND");
    }

    @Test
    void aWebpWithNoImageDataChunkIsRefused() {
        // A RIFF file that is nothing but metadata. Accepting it would publish an object no decoder
        // can render, which is worse than refusing the publish.
        byte[] metadataOnly = ImageMetadataStripper.strip(TestImages.webpWithGpsExif());
        byte[] header = Arrays.copyOf(metadataOnly, 12);
        header[4] = 4;
        header[5] = 0;
        header[6] = 0;
        header[7] = 0;

        assertThatThrownBy(() -> ImageMetadataStripper.strip(header))
                .isInstanceOf(UnstrippableImageException.class)
                .hasMessageContaining("image data");
    }

    // ------------------------------------------------------------------ fixtures

    /** JPEG bytes written with progressive encoding, so the file carries several scans. */
    private static byte[] progressiveJpeg() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam params = writer.getDefaultWriteParam();
        params.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** Splices a COM segment carrying the canary in after SOI — enough to prove the strip ran. */
    private static byte[] withExif(byte[] jpeg) {
        byte[] comment = ("Taken at " + TestImages.CANARY).getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xFE);
        out.write((comment.length + 2) >>> 8);
        out.write((comment.length + 2) & 0xFF);
        out.writeBytes(comment);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** {@code RIFF....WEBP} plus one {@code VP8L} chunk: the simple format, with nowhere to hide. */
    private static byte[] simpleWebp() {
        byte[] payload = TestImages.webpImagePayload();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        int size = 4 + 8 + payload.length;
        out.write(size);
        out.write(size >>> 8);
        out.write(size >>> 16);
        out.write(size >>> 24);
        out.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes("VP8L".getBytes(StandardCharsets.US_ASCII));
        out.write(payload.length);
        out.write(0);
        out.write(0);
        out.write(0);
        out.writeBytes(payload);
        return out.toByteArray();
    }
}
