package az.technest.whereis.storage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/**
 * Real images carrying real metadata, and the inspectors that read it back out of BYTES.
 *
 * <p>This exists because the metadata strip is the one guard in the publish path with no type
 * system behind it: a test that checks a flag or a code path would pass just as happily against a
 * strip that silently did nothing. So the fixtures below carry an actual EXIF block with actual GPS
 * coordinates plus a unique canary string, and the assertions look for those bytes in what comes
 * back over HTTP.
 *
 * <p>The JPEG and PNG bodies come from {@code ImageIO}, so they are genuinely decodable images. The
 * WebP body is hand-built: the JDK has no WebP writer at all, which is also the reason the strip
 * could never have been a decode-and-re-encode. Its {@code VP8L} payload is therefore a placeholder
 * rather than a real lossless bitstream — which costs the metadata assertion nothing, because the
 * stripper works on the RIFF container and never looks inside a bitstream, and the tests assert
 * that the payload comes back byte-identical.
 */
public final class TestImages {

    /** A string that appears ONLY inside metadata, so finding it in an output is unambiguous. */
    public static final String CANARY = "WHEREIS-EXIF-CANARY-9f2b";

    /** Real coordinates (Baku), as the rationals a camera writes. */
    private static final int[][] GPS_LATITUDE = {{40, 1}, {24, 1}, {3300, 100}};
    private static final int[][] GPS_LONGITUDE = {{49, 1}, {52, 1}, {1500, 100}};

    private TestImages() {
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * A decodable JPEG with an {@code APP1} EXIF segment holding GPS latitude/longitude, a capture
     * timestamp and the canary in {@code Make}, plus a {@code COM} comment also holding the canary —
     * because a strip that only understood EXIF would leave the comment behind.
     */
    public static byte[] jpegWithGpsExif() {
        byte[] plain = render("jpeg");
        byte[] exif = app1(exifBlob(true));
        byte[] comment = segment(0xFE, ("Taken at " + CANARY).getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2);                       // SOI
        out.write(exif, 0, exif.length);
        out.write(comment, 0, comment.length);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    /**
     * A decodable PNG with an {@code eXIf} chunk (the same GPS block, TIFF header first — PNG has no
     * {@code "Exif\0\0"} prefix) and a {@code tEXt} chunk carrying the canary.
     */
    public static byte[] pngWithGpsExif() {
        byte[] plain = render("png");
        // After the 8-byte signature and the IHDR chunk, before the image data.
        int afterIhdr = 8 + 12 + 13;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, afterIhdr);
        byte[] exif = pngChunk("eXIf", exifBlob(false));
        out.write(exif, 0, exif.length);
        byte[] text = pngChunk("tEXt",
                ("Comment\u0000" + CANARY).getBytes(StandardCharsets.ISO_8859_1));
        out.write(text, 0, text.length);
        out.write(plain, afterIhdr, plain.length - afterIhdr);
        return out.toByteArray();
    }

    /**
     * An extended-format WebP: {@code VP8X} with the EXIF and XMP feature bits set, a {@code VP8L}
     * payload, then {@code EXIF} and {@code XMP } chunks. The XMP one is deliberately an odd length,
     * so the RIFF pad byte is exercised.
     */
    public static byte[] webpWithGpsExif() {
        byte[] vp8x = new byte[10];
        vp8x[0] = (byte) (0x08 | 0x04);               // EXIF | XMP
        vp8x[4] = 0;                                  // canvas width - 1 (3 bytes LE)
        vp8x[7] = 0;                                  // canvas height - 1 (3 bytes LE)
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeRiffChunk(body, "VP8X", vp8x);
        writeRiffChunk(body, "VP8L", webpImagePayload());
        writeRiffChunk(body, "EXIF", exifBlob(false));
        writeRiffChunk(body, "XMP ", ("<x:xmpmeta><exif:GPSLatitude>40,24.55N</exif:GPSLatitude>"
                + "<tiff:Make>" + CANARY + "</tiff:Make></x:xmpmeta>")
                .getBytes(StandardCharsets.UTF_8));
        byte[] bodyBytes = body.toByteArray();
        ByteBuffer buffer = ByteBuffer.allocate(12 + bodyBytes.length);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.put(littleEndian(4 + bodyBytes.length));
        buffer.put("WEBP".getBytes(StandardCharsets.US_ASCII));
        buffer.put(bodyBytes);
        return buffer.array();
    }

    /** The {@code VP8L} payload every WebP fixture shares, so a test can assert it came back whole. */
    public static byte[] webpImagePayload() {
        return new byte[]{0x2F, 0x00, 0x00, 0x00, 0x10, 0x07, 0x10, 0x11, 0x11, (byte) 0x88,
                (byte) 0x88, (byte) 0xFE, 0x07, 0x00};
    }

    // ------------------------------------------------------------------ inspectors

    /** Every JPEG marker in order, so "there is no APPn left" is a structural assertion. */
    public static List<Integer> jpegMarkers(byte[] jpeg) {
        List<Integer> markers = new ArrayList<>();
        int cursor = 2;
        markers.add(0xD8);
        while (cursor + 1 < jpeg.length) {
            if ((jpeg[cursor] & 0xFF) != 0xFF) {
                throw new IllegalStateException("not a marker at " + cursor);
            }
            int marker = jpeg[cursor + 1] & 0xFF;
            markers.add(marker);
            if (marker == 0xD9) {
                return markers;
            }
            int length = ((jpeg[cursor + 2] & 0xFF) << 8) | (jpeg[cursor + 3] & 0xFF);
            cursor += 2 + length;
            if (marker == 0xDA) {
                // Skip entropy-coded data to the next real marker, exactly as a decoder does.
                while (cursor + 1 < jpeg.length) {
                    int next = jpeg[cursor + 1] & 0xFF;
                    if ((jpeg[cursor] & 0xFF) == 0xFF && next != 0x00 && next != 0xFF
                            && !(next >= 0xD0 && next <= 0xD7)) {
                        break;
                    }
                    cursor++;
                }
            }
        }
        return markers;
    }

    /** The compressed scan, from the SOS marker to the end — "the pixels are untouched", as bytes. */
    public static byte[] jpegScan(byte[] jpeg) {
        int cursor = 2;
        while (cursor + 3 < jpeg.length) {
            int marker = jpeg[cursor + 1] & 0xFF;
            int length = ((jpeg[cursor + 2] & 0xFF) << 8) | (jpeg[cursor + 3] & 0xFF);
            if (marker == 0xDA) {
                int from = cursor + 2 + length;
                byte[] scan = new byte[jpeg.length - from];
                System.arraycopy(jpeg, from, scan, 0, scan.length);
                return scan;
            }
            cursor += 2 + length;
        }
        throw new IllegalStateException("no scan in this JPEG");
    }

    public static List<String> pngChunkTypes(byte[] png) {
        List<String> types = new ArrayList<>();
        int cursor = 8;
        while (cursor + 12 <= png.length) {
            int length = (int) readBigEndian(png, cursor);
            types.add(new String(png, cursor + 4, 4, StandardCharsets.US_ASCII));
            cursor += 12 + length;
        }
        return types;
    }

    public static List<String> webpChunkIds(byte[] webp) {
        List<String> ids = new ArrayList<>();
        int cursor = 12;
        while (cursor + 8 <= webp.length) {
            ids.add(new String(webp, cursor, 4, StandardCharsets.US_ASCII));
            int size = (int) readLittleEndian(webp, cursor + 4);
            cursor += 8 + size + (size & 1);
        }
        return ids;
    }

    /** The payload of one RIFF chunk, or null when it is absent. */
    public static byte[] webpChunk(byte[] webp, String fourcc) {
        int cursor = 12;
        while (cursor + 8 <= webp.length) {
            String id = new String(webp, cursor, 4, StandardCharsets.US_ASCII);
            int size = (int) readLittleEndian(webp, cursor + 4);
            if (id.equals(fourcc)) {
                byte[] payload = new byte[size];
                System.arraycopy(webp, cursor + 8, payload, 0, size);
                return payload;
            }
            cursor += 8 + size + (size & 1);
        }
        return null;
    }

    /** Whether {@code needle} appears anywhere in {@code haystack} — the canary search. */
    public static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    public static boolean contains(byte[] haystack, String needle) {
        return contains(haystack, needle.getBytes(StandardCharsets.US_ASCII));
    }

    // ------------------------------------------------------------------ builders

    private static byte[] render(String format) {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                image.setRGB(x, y, (x * 60) << 16 | (y * 60) << 8 | 0x40);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("no ImageIO writer for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * A little-endian TIFF structure with IFD0 (Make, DateTime, a GPS IFD pointer) and a GPS IFD
     * holding real latitude and longitude rationals.
     *
     * @param jpegPrefixed true for JPEG's {@code APP1}, which prefixes {@code "Exif\0\0"}; false for
     *                     PNG's {@code eXIf} and WebP's {@code EXIF}, which start at the TIFF header
     */
    private static byte[] exifBlob(boolean jpegPrefixed) {
        byte[] make = zeroTerminated(CANARY);
        byte[] dateTime = zeroTerminated("2026:09:21 12:34:56");

        // Layout, all offsets measured from the TIFF header: IFD0 at 8, then its out-of-line values,
        // then the GPS IFD, then its out-of-line values.
        int ifd0At = 8;
        int ifd0Bytes = 2 + 3 * 12 + 4;
        int makeAt = ifd0At + ifd0Bytes;
        int dateTimeAt = makeAt + make.length;
        int gpsIfdAt = dateTimeAt + dateTime.length;
        int gpsIfdBytes = 2 + 4 * 12 + 4;
        int latitudeAt = gpsIfdAt + gpsIfdBytes;
        int longitudeAt = latitudeAt + 24;

        ByteBuffer tiff = ByteBuffer.allocate(longitudeAt + 24).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifd0At);

        tiff.putShort((short) 3);
        entry(tiff, 0x010F, 2, make.length, makeAt);                 // Make
        entry(tiff, 0x0132, 2, dateTime.length, dateTimeAt);         // DateTime
        entry(tiff, 0x8825, 4, 1, gpsIfdAt);                         // GPSInfoIFDPointer
        tiff.putInt(0);                                              // no IFD1
        tiff.put(make);
        tiff.put(dateTime);

        tiff.putShort((short) 4);
        entryInline(tiff, 0x0001, 2, 2, new byte[]{'N', 0, 0, 0});   // GPSLatitudeRef
        entry(tiff, 0x0002, 5, 3, latitudeAt);                       // GPSLatitude
        entryInline(tiff, 0x0003, 2, 2, new byte[]{'E', 0, 0, 0});   // GPSLongitudeRef
        entry(tiff, 0x0004, 5, 3, longitudeAt);                      // GPSLongitude
        tiff.putInt(0);
        for (int[] rational : GPS_LATITUDE) {
            tiff.putInt(rational[0]).putInt(rational[1]);
        }
        for (int[] rational : GPS_LONGITUDE) {
            tiff.putInt(rational[0]).putInt(rational[1]);
        }

        byte[] tiffBytes = tiff.array();
        if (!jpegPrefixed) {
            return tiffBytes;
        }
        byte[] blob = new byte[6 + tiffBytes.length];
        System.arraycopy("Exif".getBytes(StandardCharsets.US_ASCII), 0, blob, 0, 4);
        System.arraycopy(tiffBytes, 0, blob, 6, tiffBytes.length);
        return blob;
    }

    private static void entry(ByteBuffer buffer, int tag, int type, int count, int valueOffset) {
        buffer.putShort((short) tag).putShort((short) type).putInt(count).putInt(valueOffset);
    }

    private static void entryInline(ByteBuffer buffer, int tag, int type, int count, byte[] value) {
        buffer.putShort((short) tag).putShort((short) type).putInt(count).put(value);
    }

    private static byte[] zeroTerminated(String text) {
        byte[] ascii = text.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[ascii.length + 1];
        System.arraycopy(ascii, 0, out, 0, ascii.length);
        return out;
    }

    private static byte[] app1(byte[] payload) {
        return segment(0xE1, payload);
    }

    /** {@code FF <marker> <length including the two length bytes> <payload>}. */
    private static byte[] segment(int marker, byte[] payload) {
        int length = payload.length + 2;
        byte[] out = new byte[4 + payload.length];
        out[0] = (byte) 0xFF;
        out[1] = (byte) marker;
        out[2] = (byte) (length >>> 8);
        out[3] = (byte) length;
        System.arraycopy(payload, 0, out, 4, payload.length);
        return out;
    }

    private static byte[] pngChunk(String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        ByteBuffer buffer = ByteBuffer.allocate(12 + data.length);
        buffer.putInt(data.length).put(typeBytes).put(data).putInt((int) crc.getValue());
        return buffer.array();
    }

    private static void writeRiffChunk(ByteArrayOutputStream out, String fourcc, byte[] payload) {
        out.writeBytes(fourcc.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(littleEndian(payload.length));
        out.writeBytes(payload);
        if ((payload.length & 1) == 1) {
            out.write(0);
        }
    }

    private static byte[] littleEndian(int value) {
        return new byte[]{(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)};
    }

    private static long readBigEndian(byte[] data, int at) {
        return ((long) (data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }

    private static long readLittleEndian(byte[] data, int at) {
        return ((long) (data[at + 3] & 0xFF) << 24) | ((data[at + 2] & 0xFF) << 16)
                | ((data[at + 1] & 0xFF) << 8) | (data[at] & 0xFF);
    }
}
