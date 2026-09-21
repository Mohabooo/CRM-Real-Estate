package com.rescrm.crm.api;

import com.rescrm.crm.api.CrmDtos.CreateCustomerRequest;
import com.rescrm.crm.api.CrmDtos.CustomerResponse;
import com.rescrm.crm.api.CrmDtos.DuplicateMatchResponse;
import com.rescrm.crm.api.CrmDtos.NationalIdResponse;
import com.rescrm.crm.api.CrmDtos.PageResponse;
import com.rescrm.crm.api.CrmDtos.RevealNationalIdRequest;
import com.rescrm.crm.api.CrmDtos.SetNationalIdRequest;
import com.rescrm.crm.api.CrmDtos.UpdateCustomerRequest;
import com.rescrm.crm.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The customer book: doc 23's {@code /customers}.
 *
 * <p>The {@code /deals}, {@code /payments} and {@code /statement} sub-resources doc 23 also
 * lists belong to later epics and are not stubbed here — an endpoint that exists and returns
 * an empty list is worse than one that does not exist, because a client cannot tell the
 * difference between "no deals" and "deals were never implemented".
 *
 * <p>Reading a national identifier is a POST, not a GET. It has a side effect — the audit
 * entry — and it takes a reason, and neither belongs in a URL that ends up in a proxy log.
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private static final int MAX_PAGE_SIZE = 200;

    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping
    public PageResponse<CustomerResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int bounded = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageResponse.of(
                customers.list(PageRequest.of(Math.max(page, 0), bounded)),
                CustomerResponse::from);
    }

    @GetMapping("/duplicates")
    public List<DuplicateMatchResponse> duplicates(@RequestParam String phone) {
        return customers.findDuplicates(phone).stream()
                .map(DuplicateMatchResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public CustomerResponse get(@PathVariable UUID id) {
        return CustomerResponse.from(customers.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse create(@Valid @RequestBody CreateCustomerRequest request) {
        return CustomerResponse.from(customers.create(request.nameAr(), request.nameEn(),
                request.phone(), request.email(), request.address(), request.nationalId(),
                request.confirmDuplicate()));
    }

    @PostMapping("/{id}/details")
    public CustomerResponse updateDetails(@PathVariable UUID id,
                                          @Valid @RequestBody UpdateCustomerRequest request) {
        return CustomerResponse.from(customers.updateDetails(id, request.nameAr(),
                request.nameEn(), request.phone(), request.email(), request.address()));
    }

    @PostMapping("/{id}/national-id")
    public CustomerResponse setNationalId(@PathVariable UUID id,
                                          @Valid @RequestBody SetNationalIdRequest request) {
        return CustomerResponse.from(customers.setNationalId(id, request.nationalId()));
    }

    /** Restricted by role and audited on every call, including the reason given. */
    @PostMapping("/{id}/national-id/reveal")
    public NationalIdResponse revealNationalId(
            @PathVariable UUID id, @Valid @RequestBody RevealNationalIdRequest request) {
        return new NationalIdResponse(id, customers.revealNationalId(id, request.reason()));
    }
}
