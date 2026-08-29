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
        byte[] head = readHead(file);
        boolean matches = switch (contentType) {
            case "image/jpeg" -> startsWith(head, new int[]{0xFF, 0xD8, 0xFF});
            case "image/png" -> startsWith(head, new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
            case "image/webp" -> startsWith(head, new int[]{0x52, 0x49, 0x46, 0x46})
                    && head.length >= 12
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            default -> false;
        };
        if (!matches) {
            throw unsupported();
        }
    }

    private static byte[] readHead(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(12);
        } catch (IOException e) {
            throw new StorageException("Failed to read uploaded file", e);
        }
    }

    private static boolean startsWith(byte[] data, int[] signature) {
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
