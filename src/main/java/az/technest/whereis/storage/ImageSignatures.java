package az.technest.whereis.storage;

import az.technest.whereis.common.error.ApiException;
import az.technest.whereis.common.error.BadRequestException;
import az.technest.whereis.common.error.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

/**
 * Upload validation. The client-supplied Content-Type and filename are attacker-controlled,
 * so the actual bytes are checked against image magic numbers. SVG is deliberately excluded
 * (stored XSS when served inline).
 */
public final class ImageSignatures {

    public static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    /** Bytes needed to tell the three accepted formats apart — WebP's marker ends at byte 11. */
    static final int HEAD_BYTES = 12;

    private ImageSignatures() {
    }

    public static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException(ErrorCode.VALIDATION_ERROR, "Uploaded file is empty");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw unsupported();
        }
        // The DECLARED type must equal what the bytes actually are: a PNG announced as a JPEG is
        // refused, which is what makes the recorded content_type trustworthy later on.
        if (!contentType.equals(sniff(readHead(file)))) {
            throw unsupported();
        }
    }

    /**
     * The content type the LEADING BYTES say this is, or null for anything else. The one place
     * magic numbers are compared, so the publish-time metadata strip dispatches on exactly what
     * upload validated rather than on a second, drifting copy of the same table.
     */
    static String sniff(byte[] head) {
        if (startsWith(head, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(head, 0x52, 0x49, 0x46, 0x46) && head.length >= HEAD_BYTES
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(HEAD_BYTES);
        } catch (IOException e) {
            throw new StorageException("Failed to read uploaded file", e);
        }
    }

    private static boolean startsWith(byte[] data, int... signature) {
        if (data.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((data[i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static ApiException unsupported() {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Only JPEG, PNG and WebP images are supported");
    }
}
