package com.rescrm.crm.api;

import com.rescrm.crm.api.CrmDtos.AssignLeadRequest;
import com.rescrm.crm.api.CrmDtos.BulkReassignRequest;
import com.rescrm.crm.api.CrmDtos.BulkReassignResponse;
import com.rescrm.crm.api.CrmDtos.CaptureLeadRequest;
import com.rescrm.crm.api.CrmDtos.ConvertLeadRequest;
import com.rescrm.crm.api.CrmDtos.CustomerResponse;
import com.rescrm.crm.api.CrmDtos.DisqualifyLeadRequest;
import com.rescrm.crm.api.CrmDtos.DuplicateMatchResponse;
import com.rescrm.crm.api.CrmDtos.InterestRequest;
import com.rescrm.crm.api.CrmDtos.LeadResponse;
import com.rescrm.crm.api.CrmDtos.NextActionRequest;
import com.rescrm.crm.api.CrmDtos.PageResponse;
import com.rescrm.crm.api.CrmDtos.ReactivateLeadRequest;
import com.rescrm.crm.service.CustomerService;
import com.rescrm.crm.service.LeadService;
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
 * The lead funnel endpoints: {@code /leads} plus the {@code /assign}, {@code /convert} and
 * {@code /disqualify} sub-resources doc 23 lists, and the stage transitions doc 18 defines.
 *
 * <p>Two things are deliberately absent. There is no tenant id anywhere in a path, query or
 * body — the server takes it from the authenticated principal. And the listing takes no owner
 * or branch parameter: {@code listVisibleToCaller} narrows to the caller's own scope, so there
 * is no argument a client could widen. An agent asking for another agent's leads has nothing
 * to ask with.
 *
 * <p>Stage changes are sub-resource actions rather than a status PATCH (doc 23, principle 2),
 * which is what lets each one carry its own preconditions, permitted roles and audit entry.
 */
@RestController
@RequestMapping("/api/v1/leads")
public class LeadController {

    private static final int MAX_PAGE_SIZE = 200;

    private final LeadService leads;
    private final CustomerService customers;

    public LeadController(LeadService leads, CustomerService customers) {
        this.leads = leads;
        this.customers = customers;
    }

    // ----------------------------------------------------------------- reads

    /** Already narrowed to the caller's scope; there is no widening parameter. */
    @GetMapping
    public PageResponse<LeadResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int bounded = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return PageResponse.of(
                leads.listVisibleToCaller(PageRequest.of(Math.max(page, 0), bounded)),
                LeadResponse::from);
    }

    /** E2-S3: the follow-up queue — active leads whose next action has passed. */
    @GetMapping("/stale")
    public List<LeadResponse> stale() {
        return leads.staleQueue().stream().map(LeadResponse::from).toList();
    }

    /**
     * E2-S1: the duplicate check a client runs while the user is still typing, so the warning
     * arrives before the submit rather than as a rejected request.
     */
    @GetMapping("/duplicates")
    public List<DuplicateMatchResponse> duplicates(@RequestParam String phone) {
        return leads.findDuplicates(phone).stream().map(DuplicateMatchResponse::from).toList();
    }

    @GetMapping("/{id}")
    public LeadResponse get(@PathVariable UUID id) {
        return LeadResponse.from(leads.get(id));
    }

    // --------------------------------------------------------------- capture

    /**
     * E2-S1. A duplicate phone returns 409 with the matches in the error details; the same
     * request with {@code confirmDuplicate} set creates the lead anyway.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LeadResponse capture(@Valid @RequestBody CaptureLeadRequest request) {
        return LeadResponse.from(leads.capture(request.name(), request.phone(), request.email(),
                request.source(), request.branchId(), request.interest(),
                request.confirmDuplicate()));
    }

    // ------------------------------------------------------------ transitions

    @PostMapping("/{id}/assign")
    public LeadResponse assign(@PathVariable UUID id,
                               @Valid @RequestBody AssignLeadRequest request) {
        return LeadResponse.from(leads.assign(id, request.ownerUserId(), request.reason()));
    }

    /** E2-S2: fifty leads move as one transaction and one audit entry, or none do. */
    @PostMapping("/bulk-reassign")
    public BulkReassignResponse bulkReassign(@Valid @RequestBody BulkReassignRequest request) {
        int moved = leads.bulkReassign(request.leadIds(), request.ownerUserId(),
                request.reason());
        return new BulkReassignResponse(moved, request.ownerUserId());
    }

    /** Requires a logged activity first; 422 otherwise. */
    @PostMapping("/{id}/contact")
    public LeadResponse contact(@PathVariable UUID id) {
        return LeadResponse.from(leads.recordContact(id));
    }

    @PostMapping("/{id}/qualify")
    public LeadResponse qualify(@PathVariable UUID id) {
        return LeadResponse.from(leads.qualify(id));
    }

    @PostMapping("/{id}/disqualify")
    public LeadResponse disqualify(@PathVariable UUID id,
                                   @Valid @RequestBody DisqualifyLeadRequest request) {
        return LeadResponse.from(leads.disqualify(id, request.reason()));
    }

    @PostMapping("/{id}/reactivate")
    public LeadResponse reactivate(@PathVariable UUID id,
                                   @Valid @RequestBody ReactivateLeadRequest request) {
        return LeadResponse.from(
                leads.reactivate(id, request.ownerUserId(), request.reason()));
    }

    /**
     * E2-S4. Conversion lives here rather than on {@code /customers} because doc 23 puts it
     * here, and because the lead is what the caller is acting on: the customer is the result.
     */
    @PostMapping("/{id}/convert")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse convert(@PathVariable UUID id,
                                    @Valid @RequestBody ConvertLeadRequest request) {
        return CustomerResponse.from(customers.convertLead(id, request.nameAr(),
                request.nameEn(), request.address(), request.nationalId()));
    }

    // ------------------------------------------------------------ scheduling

    @PostMapping("/{id}/next-action")
    public LeadResponse setNextAction(@PathVariable UUID id,
                                      @Valid @RequestBody NextActionRequest request) {
        return LeadResponse.from(leads.setNextAction(id, request.at()));
    }

    @PostMapping("/{id}/interest")
    public LeadResponse captureInterest(@PathVariable UUID id,
                                        @RequestBody InterestRequest request) {
        return LeadResponse.from(leads.captureInterest(id, request.interest()));
    }
}
