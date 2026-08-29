package az.technest.whereis.assistant;

import az.technest.whereis.assistant.dto.AssistantSearchRequest;
import az.technest.whereis.assistant.dto.AssistantSearchResponse;
import az.technest.whereis.assistant.dto.ImageAnalyzeResponse;
import az.technest.whereis.assistant.dto.RememberRequest;
import az.technest.whereis.assistant.dto.RememberResponse;
import az.technest.whereis.common.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantService assistantService;

    @PostMapping("/remember")
    public RememberResponse remember(@Valid @RequestBody RememberRequest request) {
        return assistantService.remember(CurrentUser.id(), request.message());
    }

    @PostMapping("/search")
    public AssistantSearchResponse search(@Valid @RequestBody AssistantSearchRequest request) {
        return assistantService.search(CurrentUser.id(), request.query());
    }

    @PostMapping(value = "/images/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImageAnalyzeResponse analyzeImage(@RequestPart("file") MultipartFile file) {
        return assistantService.analyzeImage(CurrentUser.id(), file);
    }
}
