package com.rescrm.crm.api;

import com.rescrm.crm.api.CrmDtos.ActivityResponse;
import com.rescrm.crm.api.CrmDtos.LogActivityRequest;
import com.rescrm.crm.domain.ActivitySubject;
import com.rescrm.crm.service.ActivityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The contact timeline: doc 23's {@code /activities} (E2-S3).
 *
 * <p>A timeline is always read for one subject. There is no unfiltered listing, because the
 * only sensible answer to "show me every activity in the tenant" is a report, and reporting is
 * Epic 11. Asking for a subject also means every read runs through the same visibility check
 * as a write: the lead or customer must exist in the caller's tenant and be visible to them,
 * or the answer is 404.
 */
@RestController
@RequestMapping("/api/v1/activities")
public class ActivityController {

    private final ActivityService activities;

    public ActivityController(ActivityService activities) {
        this.activities = activities;
    }

    @GetMapping
    public List<ActivityResponse> timeline(@RequestParam ActivitySubject subjectType,
                                           @RequestParam UUID subjectId) {
        return activities.timeline(subjectType, subjectId).stream()
                .map(ActivityResponse::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ActivityResponse log(@Valid @RequestBody LogActivityRequest request) {
        return ActivityResponse.from(activities.log(request.subjectType(), request.subjectId(),
                request.type(), request.body(), request.occurredAt()));
    }
}
