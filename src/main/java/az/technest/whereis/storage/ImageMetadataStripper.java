package az.technest.whereis.storage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Rewrites an image's CONTAINER so the result carries no metadata, without ever decoding a pixel.
 *
 * <p><strong>Why this exists.</strong> A camera writes {@code GPSLatitude}, {@code GPSLongitude},
 * {@code DateTimeOriginal} and the device make, model and serial number into the file. A marketplace
 * listing deliberately exposes only a CITY — precisely so a seller is not telling strangers where
 * their things are — and publishing those bytes verbatim hands a crawler the coordinates anyway.
 * The publish path is byte-for-byte the only place this can be closed: the private copy the owner
 * uploaded is theirs and is left exactly as it arrived.
 *
 * <p><strong>Why a container rewrite and not a re-encode.</strong> Re-encoding a JPEG loses quality
 * on every publish, and it cannot be done for all three accepted formats at all: the JDK has no
 * WebP writer, so "decode and write it out again" would silently convert format or need a native
 * encoder. Rewriting the container copies the compressed image data across BYTE-IDENTICALLY, which
 * is both lossless and far easier to verify.
 *
 * <p><strong>Why no library.</strong> No single dependency covers the three formats this
 * application accepts. Apache Commons Imaging can losslessly drop a JPEG's EXIF segment but has no
 * PNG or WebP writer at all; {@code metadata-extractor} and Tika are read-only; Thumbnailator and
 * plain {@code ImageIO} re-encode. A JPEG-only strip is simply a hole in the other two formats, and
 * a dependency that covers one third of the problem would still leave these rewriters to write. So
 * there is no new dependency: three walks over a length-prefixed chunk list, no decoding, no native
 * code.
 *
 * <p><strong>The rule, and it is the same rule in all three formats: keep only what a decoder needs
 * to reproduce the pixels, and drop everything else.</strong> Not "drop the tags we know about" — an
 * allowlist cannot be outrun by a metadata box nobody thought of, and every format here has more
 * than one place to hide a GPS coordinate (JPEG: EXIF in APP1, XMP in APP1, IPTC in APP13, a
 * free-text COM; PNG: {@code eXIf}, {@code tEXt}, {@code zTXt}, {@code iTXt}; WebP: {@code EXIF} and
 * {@code XMP } chunks). Nothing reaches the output that was not classified on the way through.
 *
 * <p><strong>What that costs, stated rather than discovered.</strong> Colour-management data goes
 * with the rest: a JPEG's ICC profile (APP2) and Adobe colour-transform marker (APP14), and a PNG's
 * {@code iCCP}/{@code gAMA}/{@code sRGB}. A wide-gamut photo therefore renders as sRGB in a
 * published listing — slightly more saturated than the original. That is a deliberate trade for a
 * rule a reviewer can check by reading five lines, on an image whose job is to show what a used
 * phone looks like. The owner's own copy is untouched.
 */
final class ImageMetadataStripper {

    // ---------------------------------------------------------------- JPEG markers
    private static final int MARKER_PREFIX = 0xFF;
    private static final int SOI = 0xD8;
    private static final int EOI = 0xD9;
    private static final int SOS = 0xDA;
    private static final int TEM = 0x01;
    private static final int RST0 = 0xD0;
    private static final int RST7 = 0xD7;
    private static final int APP0 = 0xE0;
    private static final int APP15 = 0xEF;
    private static final int COM = 0xFE;
    private static final int STUFFED = 0x00;

    // ---------------------------------------------------------------- PNG
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final int PNG_CHUNK_OVERHEAD = 12;

    /**
     * The four critical chunks, {@code tRNS} (transparency is pixel data in a palette or greyscale
     * image, not decoration) and the three APNG chunks so an animation survives as an animation.
     * Everything else — including every text and EXIF chunk — is dropped.
     */
    private static final Set<String> PNG_KEEP =
            Set.of("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "acTL", "fcTL", "fdAT");

    // ---------------------------------------------------------------- WebP / RIFF
    private static final int RIFF_HEADER_BYTES = 12;
    private static final int RIFF_CHUNK_HEADER_BYTES = 8;

    /** {@code ICCP}, {@code EXIF} and {@code XMP } are the three this omits. */
    private static final Set<String> WEBP_KEEP = Set.of("VP8X", "ALPH", "ANIM", "ANMF", "VP8 ", "VP8L");

    private static final Set<String> WEBP_IMAGE_CHUNKS = Set.of("VP8 ", "VP8L", "ANMF");

    /**
     * The ICCP, EXIF and XMP feature bits of {@code VP8X}'s first payload byte
     * (libwebp's {@code WebPFeatureFlags}: ICCP {@code 0x20}, EXIF {@code 0x08}, XMP {@code 0x04}).
     * Dropping the chunks without clearing these leaves a header advertising data that is gone,
     * which a strict decoder is entitled to reject.
     */
    private static final int VP8X_METADATA_FLAGS = 0x20 | 0x08 | 0x04;

    private ImageMetadataStripper() {
    }

    /**
     * @param image the stored object's bytes
     * @return the same image with every metadata container removed and the compressed image data
     *         copied across unchanged
     * @throws UnstrippableImageException if the container is not one of the three accepted formats
     *         or does not parse — never a partially stripped result, and never the input
     */
    static byte[] strip(byte[] image) {
        String format = ImageSignatures.sniff(image);
        if (format == null) {
            throw new UnstrippableImageException("unrecognised image container");
        }
        return switch (format) {
            case "image/jpeg" -> stripJpeg(image);
            case "image/png" -> stripPng(image);
            case "image/webp" -> stripWebp(image);
            // Unreachable while sniff() and this switch agree; a new accepted format must land here
            // as a refusal rather than as an unstripped publish.
            default -> throw new UnstrippableImageException("no metadata stripper for " + format);
        };
    }

    // ------------------------------------------------------------------ JPEG

    /**
     * Walks the marker segments, copying everything except {@code APP0}–{@code APP15} and
     * {@code COM}. Entropy-coded scan data is copied verbatim between its {@code SOS} header and the
     * next marker, so progressive JPEGs (several scans) survive and nothing after the final
     * {@code EOI} does — appended trailing bytes are the other place a tool hides metadata.
     */
    private static byte[] stripJpeg(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        writeMarker(out, SOI);
        int cursor = 2;
        while (cursor < in.length) {
            if (unsigned(in, cursor) != MARKER_PREFIX) {
                throw new UnstrippableImageException("JPEG segment does not start with 0xFF at " + cursor);
            }
            // Any number of 0xFF fill bytes may precede a marker.
            int markerAt = cursor;
            while (markerAt < in.length && unsigned(in, markerAt) == MARKER_PREFIX) {
                markerAt++;
            }
            if (markerAt >= in.length) {
                throw new UnstrippableImageException("JPEG ends inside a marker");
            }
            int marker = unsigned(in, markerAt);
            int payloadAt = markerAt + 1;

            if (marker == EOI) {
                writeMarker(out, EOI);
                return out.toByteArray();
            }
            if (marker == SOI || marker == TEM || (marker >= RST0 && marker <= RST7)) {
                writeMarker(out, marker);
                cursor = payloadAt;
                continue;
            }

            // Every other marker carries a big-endian length that INCLUDES its own two bytes.
            if (payloadAt + 1 >= in.length) {
                throw new UnstrippableImageException("truncated JPEG segment header");
            }
            int length = (unsigned(in, payloadAt) << 8) | unsigned(in, payloadAt + 1);
            if (length < 2 || payloadAt + length > in.length) {
                throw new UnstrippableImageException("bad JPEG segment length at " + payloadAt);
            }

            if (marker == SOS) {
                writeMarker(out, SOS);
                out.write(in, payloadAt, length);
                int scanAt = payloadAt + length;
                int scanEnd = nextMarkerAfterScan(in, scanAt);
                out.write(in, scanAt, scanEnd - scanAt);
                cursor = scanEnd;
                continue;
            }
            if (!((marker >= APP0 && marker <= APP15) || marker == COM)) {
                writeMarker(out, marker);
                out.write(in, payloadAt, length);
            }
            cursor = payloadAt + length;
        }
        throw new UnstrippableImageException("JPEG ended without an end-of-image marker");
    }

    /**
     * The index of the {@code 0xFF} that begins the next real marker after entropy-coded data —
     * how every JPEG decoder finds the end of a scan without decoding it. {@code 0xFF00} is a
     * stuffed data byte, {@code 0xFFFF} is fill, and {@code RSTn} belongs to the scan itself.
     */
    private static int nextMarkerAfterScan(byte[] in, int from) {
        for (int i = from; i + 1 < in.length; i++) {
            if (unsigned(in, i) != MARKER_PREFIX) {
                continue;
            }
            int candidate = unsigned(in, i + 1);
            if (candidate == STUFFED || candidate == MARKER_PREFIX
                    || (candidate >= RST0 && candidate <= RST7)) {
                continue;
            }
            return i;
        }
        throw new UnstrippableImageException("JPEG scan is not terminated by a marker");
    }

    // ------------------------------------------------------------------ PNG

    /**
     * Walks the chunk list, copying the allowlisted chunks byte for byte — length, type, data and
     * CRC together, so no checksum is ever recomputed and a kept chunk cannot be corrupted by this
     * pass. Everything after {@code IEND} is dropped with the rest.
     */
    private static byte[] stripPng(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.write(PNG_SIGNATURE, 0, PNG_SIGNATURE.length);
        int cursor = PNG_SIGNATURE.length;
        while (cursor + PNG_CHUNK_OVERHEAD <= in.length) {
            long dataLength = readBigEndianInt(in, cursor);
            if (dataLength > in.length - cursor - PNG_CHUNK_OVERHEAD) {
                throw new UnstrippableImageException("bad PNG chunk length at " + cursor);
            }
            String type = fourCharacterCode(in, cursor + 4);
            int chunkLength = (int) dataLength + PNG_CHUNK_OVERHEAD;
            if (PNG_KEEP.contains(type)) {
                out.write(in, cursor, chunkLength);
            }
            cursor += chunkLength;
            if ("IEND".equals(type)) {
                return out.toByteArray();
            }
        }
        throw new UnstrippableImageException("PNG has no IEND chunk");
    }

    // ------------------------------------------------------------------ WebP

    /**
     * Walks the RIFF chunk list and rebuilds the 12-byte file header, because dropping a chunk
     * changes the declared RIFF size. A kept chunk's odd payload is re-padded to even, synthesising
     * the pad byte if a writer omitted it at end of file.
     */
    private static byte[] stripWebp(byte[] in) {
        long declaredSize = readLittleEndianInt(in, 4);
        // The declared size covers everything after the 8-byte RIFF header. A file may be longer
        // (trailing junk, which is dropped) but never shorter than what it claims.
        int end = (int) Math.min(in.length, RIFF_CHUNK_HEADER_BYTES + declaredSize);
        if (end < RIFF_HEADER_BYTES) {
            throw new UnstrippableImageException("bad RIFF size");
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(in.length);
        boolean sawImageData = false;
        int cursor = RIFF_HEADER_BYTES;
        while (cursor + RIFF_CHUNK_HEADER_BYTES <= end) {
            String fourcc = fourCharacterCode(in, cursor);
            long size = readLittleEndianInt(in, cursor + 4);
            int payloadAt = cursor + RIFF_CHUNK_HEADER_BYTES;
            if (size > end - payloadAt) {
                throw new UnstrippableImageException("bad WebP chunk size for " + fourcc);
            }
            if (WEBP_KEEP.contains(fourcc)) {
                body.write(in, cursor, RIFF_CHUNK_HEADER_BYTES);
                if ("VP8X".equals(fourcc) && size >= 1) {
                    body.write(unsigned(in, payloadAt) & ~VP8X_METADATA_FLAGS);
                    body.write(in, payloadAt + 1, (int) size - 1);
                } else {
                    body.write(in, payloadAt, (int) size);
                }
                if ((size & 1) == 1) {
                    body.write(0);
                }
                sawImageData |= WEBP_IMAGE_CHUNKS.contains(fourcc);
            }
            cursor = payloadAt + (int) size + (int) (size & 1);
        }
        if (!sawImageData) {
            throw new UnstrippableImageException("WebP has no image data chunk");
        }
        byte[] bodyBytes = body.toByteArray();
        byte[] out = new byte[RIFF_HEADER_BYTES + bodyBytes.length];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, out, 0, 4);
        writeLittleEndianInt(out, 4, 4 + bodyBytes.length);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, out, 8, 4);
        System.arraycopy(bodyBytes, 0, out, RIFF_HEADER_BYTES, bodyBytes.length);
        return out;
    }

    // ------------------------------------------------------------------ bytes

    private static void writeMarker(ByteArrayOutputStream out, int marker) {
        out.write(MARKER_PREFIX);
        out.write(marker);
    }

    private static int unsigned(byte[] data, int index) {
        return data[index] & 0xFF;
    }

    /** {@code long} because a chunk length is an UNSIGNED 32-bit field and an int would go negative. */
    private static long readBigEndianInt(byte[] data, int at) {
        return ((long) unsigned(data, at) << 24) | (unsigned(data, at + 1) << 16)
                | (unsigned(data, at + 2) << 8) | unsigned(data, at + 3);
    }

    private static long readLittleEndianInt(byte[] data, int at) {
        return ((long) unsigned(data, at + 3) << 24) | (unsigned(data, at + 2) << 16)
                | (unsigned(data, at + 1) << 8) | unsigned(data, at);
    }

    private static void writeLittleEndianInt(byte[] data, int at, int value) {
        data[at] = (byte) value;
        data[at + 1] = (byte) (value >>> 8);
        data[at + 2] = (byte) (value >>> 16);
        data[at + 3] = (byte) (value >>> 24);
    }

    private static String fourCharacterCode(byte[] data, int at) {
        return new String(data, at, 4, StandardCharsets.US_ASCII);
    }
}
