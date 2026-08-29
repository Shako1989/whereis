package az.technest.whereis.location;

import az.technest.whereis.common.security.CurrentUser;
import az.technest.whereis.location.dto.CreateLocationRequest;
import az.technest.whereis.location.dto.LocationResponse;
import az.technest.whereis.location.dto.LocationTreeNode;
import az.technest.whereis.location.dto.UpdateLocationRequest;
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
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;

    @PostMapping("/spaces/{spaceId}/locations")
    @ResponseStatus(HttpStatus.CREATED)
    public LocationResponse create(@PathVariable UUID spaceId, @Valid @RequestBody CreateLocationRequest request) {
        return locationService.create(CurrentUser.id(), spaceId, request);
    }

    @GetMapping("/spaces/{spaceId}/locations")
    public List<LocationResponse> listBySpace(@PathVariable UUID spaceId) {
        return locationService.listBySpace(CurrentUser.id(), spaceId);
    }

    @GetMapping("/spaces/{spaceId}/location-tree")
    public List<LocationTreeNode> tree(@PathVariable UUID spaceId) {
        return locationService.tree(CurrentUser.id(), spaceId);
    }

    @GetMapping("/locations/{locationId}")
    public LocationResponse get(@PathVariable UUID locationId) {
        return locationService.get(CurrentUser.id(), locationId);
    }

    @GetMapping("/locations/{locationId}/children")
    public List<LocationResponse> children(@PathVariable UUID locationId) {
        return locationService.children(CurrentUser.id(), locationId);
    }

    @PutMapping("/locations/{locationId}")
    public LocationResponse update(@PathVariable UUID locationId, @Valid @RequestBody UpdateLocationRequest request) {
        return locationService.update(CurrentUser.id(), locationId, request);
    }

    @DeleteMapping("/locations/{locationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID locationId) {
        locationService.delete(CurrentUser.id(), locationId);
    }
}
