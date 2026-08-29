package az.technest.whereis.storage;

import az.technest.whereis.common.security.CurrentUser;
import az.technest.whereis.storage.dto.ItemFileResponse;
import az.technest.whereis.storage.dto.PresignedUrlResponse;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/items/{itemId}/files")
@RequiredArgsConstructor
public class ItemFileController {

    private final FileStorageService fileStorageService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ItemFileResponse upload(@PathVariable UUID itemId,
                                   @RequestPart("file") MultipartFile file,
                                   @RequestParam(defaultValue = "false") boolean primary) {
        return fileStorageService.upload(CurrentUser.id(), itemId, file, primary);
    }

    @GetMapping
    public List<ItemFileResponse> list(@PathVariable UUID itemId) {
        return fileStorageService.list(CurrentUser.id(), itemId);
    }

    @DeleteMapping("/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID itemId, @PathVariable UUID fileId) {
        fileStorageService.delete(CurrentUser.id(), itemId, fileId);
    }

    @GetMapping("/{fileId}/url")
    public PresignedUrlResponse url(@PathVariable UUID itemId, @PathVariable UUID fileId) {
        return fileStorageService.presign(CurrentUser.id(), itemId, fileId);
    }
}
