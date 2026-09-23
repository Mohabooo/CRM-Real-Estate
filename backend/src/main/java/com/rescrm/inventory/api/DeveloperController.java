package com.rescrm.inventory.api;

import com.rescrm.inventory.api.InventoryDtos.DeveloperResponse;
import com.rescrm.inventory.api.InventoryDtos.PageResponse;
import com.rescrm.inventory.api.InventoryDtos.ReasonRequest;
import com.rescrm.inventory.api.InventoryDtos.RegisterDeveloperRequest;
import com.rescrm.inventory.api.InventoryDtos.UpdateDeveloperRequest;
import com.rescrm.inventory.service.DeveloperService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Doc 23's {@code /developers} (E3-S2).
 *
 * <p>The {@code /developers/{id}/commission-rules} sub-resource doc 23 also lists belongs to
 * Epic 8 and is not stubbed here. E3-S2's "an inbound commission rule is required before a
 * deal on its inventory can activate" is a precondition of deal activation, so it is enforced
 * where activation lives rather than asserted by an endpoint that cannot yet check it.
 *
 * <p>There is no delete. A developer with projects behind it is part of the financial record,
 * and the restricting foreign key would refuse the delete anyway; having no endpoint means
 * nobody has to discover that at the wrong moment.
 */
@RestController
@RequestMapping("/api/v1/developers")
public class DeveloperController {

    private static final int MAX_PAGE_SIZE = 200;

    private final DeveloperService developers;

    public DeveloperController(DeveloperService developers) {
        this.developers = developers;
    }

    @GetMapping
    public PageResponse<DeveloperResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int bounded = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageResponse.of(developers.list(PageRequest.of(Math.max(page, 0), bounded)),
                DeveloperResponse::from);
    }

    @GetMapping("/{id}")
    public DeveloperResponse get(@PathVariable UUID id) {
        return DeveloperResponse.from(developers.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeveloperResponse register(@Valid @RequestBody RegisterDeveloperRequest request) {
        return DeveloperResponse.from(developers.register(request.name(), request.contact(),
                request.paymentTermsNote()));
    }

    @PatchMapping("/{id}")
    public DeveloperResponse update(@PathVariable UUID id,
                                    @Valid @RequestBody UpdateDeveloperRequest request) {
        return DeveloperResponse.from(developers.updateDetails(id, request.name(),
                request.contact(), request.paymentTermsNote()));
    }

    @PostMapping("/{id}/deactivate")
    public DeveloperResponse deactivate(@PathVariable UUID id,
                                        @RequestBody(required = false) ReasonRequest request) {
        return DeveloperResponse.from(
                developers.deactivate(id, request == null ? null : request.reason()));
    }

    @PostMapping("/{id}/reactivate")
    public DeveloperResponse reactivate(@PathVariable UUID id) {
        return DeveloperResponse.from(developers.reactivate(id));
    }
}
