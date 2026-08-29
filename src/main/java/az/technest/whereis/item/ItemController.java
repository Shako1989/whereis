package az.technest.whereis.item;

import az.technest.whereis.common.security.CurrentUser;
import az.technest.whereis.item.dto.CreateItemRequest;
import az.technest.whereis.item.dto.ItemHistoryResponse;
import az.technest.whereis.item.dto.ItemResponse;
import az.technest.whereis.item.dto.MoveItemRequest;
import az.technest.whereis.item.dto.UpdateItemRequest;
import az.technest.whereis.search.SearchService;
import az.technest.whereis.search.dto.ItemSearchResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/items")
@RequiredArgsConstructor
public class ItemController {

    private final ItemService itemService;
    private final SearchService searchService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ItemResponse create(@Valid @RequestBody CreateItemRequest request) {
        return itemService.create(CurrentUser.id(), request);
    }

    @GetMapping
    public Page<ItemResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "updatedAt,desc") String sort,
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return itemService.list(CurrentUser.id(), page, size, sort, includeArchived);
    }

    @GetMapping("/search")
    public List<ItemSearchResult> search(@RequestParam("q") String query,
                                         @RequestParam(defaultValue = "20") int limit) {
        return searchService.search(CurrentUser.id(), query, limit);
    }

    @GetMapping("/{itemId}")
    public ItemResponse get(@PathVariable UUID itemId) {
        return itemService.get(CurrentUser.id(), itemId);
    }

    @PutMapping("/{itemId}")
    public ItemResponse update(@PathVariable UUID itemId, @Valid @RequestBody UpdateItemRequest request) {
        return itemService.update(CurrentUser.id(), itemId, request);
    }

    @PostMapping("/{itemId}/move")
    public ItemResponse move(@PathVariable UUID itemId, @Valid @RequestBody MoveItemRequest request) {
        return itemService.moveItem(CurrentUser.id(), itemId, request);
    }

    @GetMapping("/{itemId}/history")
    public List<ItemHistoryResponse> history(@PathVariable UUID itemId) {
        return itemService.history(CurrentUser.id(), itemId);
    }

    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID itemId) {
        itemService.delete(CurrentUser.id(), itemId);
    }
}
