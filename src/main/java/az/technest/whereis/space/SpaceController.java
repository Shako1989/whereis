package az.technest.whereis.space;

import az.technest.whereis.common.security.CurrentUser;
import az.technest.whereis.space.dto.CreateSpaceRequest;
import az.technest.whereis.space.dto.SpaceResponse;
import az.technest.whereis.space.dto.UpdateSpaceRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spaces")
@RequiredArgsConstructor
public class SpaceController {

    private final SpaceService spaceService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SpaceResponse create(@Valid @RequestBody CreateSpaceRequest request) {
        return spaceService.create(CurrentUser.id(), request);
    }

    @GetMapping
    public List<SpaceResponse> list() {
        return spaceService.list(CurrentUser.id());
    }

    @GetMapping("/{spaceId}")
    public SpaceResponse get(@PathVariable UUID spaceId) {
        return spaceService.get(CurrentUser.id(), spaceId);
    }

    @PutMapping("/{spaceId}")
    public SpaceResponse update(@PathVariable UUID spaceId, @Valid @RequestBody UpdateSpaceRequest request) {
        return spaceService.update(CurrentUser.id(), spaceId, request);
    }

    @DeleteMapping("/{spaceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID spaceId) {
        spaceService.delete(CurrentUser.id(), spaceId);
    }
}
