package az.technest.whereis.storage;

/**
 * The image's container could not be rewritten, so its metadata cannot be PROVEN gone.
 *
 * <p>Deliberately not an {@code ApiException}: it carries no status and no error code, because the
 * answer depends on what was being attempted. {@code FileStorageService.publishPhoto} turns it into
 * a 409 that asks for a different photo. <strong>What no caller may ever do is fall back to storing
 * the original bytes</strong> — that is this whole guard reopening quietly, and it is the one guard
 * in the publish path with no type system behind it.
 */
class UnstrippableImageException extends RuntimeException {

    UnstrippableImageException(String reason) {
        super(reason);
    }
}
