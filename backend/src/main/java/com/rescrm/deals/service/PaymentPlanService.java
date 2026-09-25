package com.rescrm.deals.service;

import com.rescrm.deals.domain.CustomerPaymentPlan;
import com.rescrm.deals.domain.Deal;
import com.rescrm.deals.domain.Installment;
import com.rescrm.deals.domain.PaymentPlanTemplate;
import com.rescrm.deals.repository.CustomerPaymentPlanRepository;
import com.rescrm.deals.repository.DealRepository;
import com.rescrm.deals.repository.InstallmentRepository;
import com.rescrm.deals.repository.PaymentPlanTemplateRepository;
import com.rescrm.finance.schedule.InstallmentKind;
import com.rescrm.finance.schedule.PaymentSchedule;
import com.rescrm.finance.schedule.PaymentScheduleGenerator;
import com.rescrm.finance.schedule.PlanTerms;
import com.rescrm.inventory.domain.Project;
import com.rescrm.inventory.domain.Unit;
import com.rescrm.inventory.service.ProjectService;
import com.rescrm.inventory.service.UnitService;
import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditWriter;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.money.Money;
import com.rescrm.platform.security.AuthorizationService;
import com.rescrm.platform.security.Role;
import com.rescrm.platform.security.SecurityContext;
import com.rescrm.platform.tenancy.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Payment plan templates (E5-S3) and the schedules made from them (E5-S4).
 *
 * <p>Two things live here and the division matters. A template is a shape somebody offers —
 * "10% down, 8 years quarterly, 5% on delivery". A plan is one customer's instance of that
 * shape, with the terms copied and the rows generated. Nothing reads a template again once
 * a plan exists, which is what makes TPL-001 structural rather than a rule to remember: an
 * edit to a template cannot reach a live customer's schedule because no code path leads
 * there.
 *
 * <p>Applying a template WRITES the schedule as a draft plan and its rows, and the preview
 * then reads those rows back. That is deliberate and it is the whole answer to "does the
 * preview match what gets signed": it is not a matching schedule, it is the same one. A
 * preview that generated on the fly would be a second implementation of doc 17's arithmetic,
 * and the day the two disagreed the customer would be holding whichever was wrong.
 *
 * <p>Everything generated here is an EXPECTED obligation. What was actually paid, how it was
 * allocated and what is outstanding belong to payments and collections in Epic 6, and no
 * column in this module can express them.
 */
@Service
public class PaymentPlanService {

    private final PaymentPlanTemplateRepository templates;
    private final CustomerPaymentPlanRepository plans;
    private final InstallmentRepository installments;
    private final DealRepository deals;
    private final UnitService units;
    private final ProjectService projects;
    private final AuthorizationService authorization;
    private final AuditWriter audit;

    public PaymentPlanService(PaymentPlanTemplateRepository templates,
                              CustomerPaymentPlanRepository plans,
                              InstallmentRepository installments, DealRepository deals,
                              UnitService units, ProjectService projects,
                              AuthorizationService authorization, AuditWriter audit) {
        this.templates = templates;
        this.plans = plans;
        this.installments = installments;
        this.deals = deals;
        this.units = units;
        this.projects = projects;
        this.authorization = authorization;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ E5-S3 templates

    @Transactional
    public PaymentPlanTemplate createTemplate(UUID projectId, String name, String shorthandLabel,
                                              PaymentPlanTemplate.Shape shape) {
        requireTemplateAdministration();
        UUID tenantId = TenantContext.require();
        if (projectId != null) {
            projects.get(projectId);
        }

        PaymentPlanTemplate template;
        try {
            template = PaymentPlanTemplate.create(tenantId, projectId, name, shorthandLabel, shape);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        template.recordActor(SecurityContext.require().userId(), true);

        PaymentPlanTemplate saved = saveTemplateExclusive(template, name);
        audit.recordCreation(AuditAction.PAYMENT_PLAN_TEMPLATE_CREATED, "PaymentPlanTemplate",
                saved.id(), describe(saved));
        return saved;
    }

    /**
     * E5-S3. Corrects a template's terms and label.
     *
     * <p>Live plans are untouched, and not because this method is careful — because they
     * copied their terms and nothing here can reach them (TPL-001).
     */
    @Transactional
    public PaymentPlanTemplate updateTemplate(UUID templateId, String name, String shorthandLabel,
                                              PaymentPlanTemplate.Shape shape) {
        requireTemplateAdministration();
        PaymentPlanTemplate template = getTemplate(templateId);
        Map<String, Object> before = describe(template);

        try {
            template.rename(name, shorthandLabel);
            template.restate(shape);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
        template.recordActor(SecurityContext.require().userId(), false);
        PaymentPlanTemplate saved = saveTemplateExclusive(template, name);

        audit.record(AuditAction.PAYMENT_PLAN_TEMPLATE_UPDATED, "PaymentPlanTemplate",
                saved.id(), before, describe(saved), null);
        return saved;
    }

    /** TPL-002. Retires a template from the offered list; instances carry on unchanged. */
    @Transactional
    public PaymentPlanTemplate archiveTemplate(UUID templateId) {
        requireTemplateAdministration();
        PaymentPlanTemplate template = getTemplate(templateId);
        template.archive();
        template.recordActor(SecurityContext.require().userId(), false);
        PaymentPlanTemplate saved = templates.save(template);

        audit.record(AuditAction.PAYMENT_PLAN_TEMPLATE_ARCHIVED, "PaymentPlanTemplate",
                saved.id(), Map.of("active", true), Map.of("active", false), null);
        return saved;
    }

    @Transactional
    public PaymentPlanTemplate restoreTemplate(UUID templateId) {
        requireTemplateAdministration();
        PaymentPlanTemplate template = getTemplate(templateId);
        template.restore();
        template.recordActor(SecurityContext.require().userId(), false);
        PaymentPlanTemplate saved = templates.save(template);

        audit.record(AuditAction.PAYMENT_PLAN_TEMPLATE_RESTORED, "PaymentPlanTemplate",
                saved.id(), Map.of("active", false), Map.of("active", true), null);
        return saved;
    }

    @Transactional(readOnly = true)
    public PaymentPlanTemplate getTemplate(UUID templateId) {
        return templates.findByTenantIdAndId(TenantContext.require(), templateId)
                .orElseThrow(() -> ApiException.notFound("Payment plan template"));
    }

    /** Every template, archived ones included: this is the administration list. */
    @Transactional(readOnly = true)
    public List<PaymentPlanTemplate> listTemplates() {
        return templates.findAllForTenant(TenantContext.require());
    }

    /**
     * The templates an agent may actually apply to this deal: active, and either scoped to
     * its project or offered tenant-wide.
     */
    @Transactional(readOnly = true)
    public List<PaymentPlanTemplate> templatesOfferedForDeal(UUID dealId) {
        UUID tenantId = TenantContext.require();
        Deal deal = requireDeal(tenantId, dealId);
        return templates.findOfferedFor(tenantId, units.get(deal.unitId()).projectId());
    }

    // ------------------------------------------------------------------ E5-S4 schedules

    /**
     * Generates this deal's schedule from a template and stores it as a draft plan.
     *
     * <p>Run again, it regenerates in place rather than accumulating a second draft: doc 18
     * section 5's "draft → draft (regenerate)". The existing rows are deleted and replaced,
     * which is safe precisely because a draft plan has no allocations against it — the
     * moment one does, Epic 6's R-INST-9 closes this path.
     *
     * <p>The generator is given {@link PlanTerms} and nothing else. It is not told the
     * commercial model, the tenant or the deal, so FIN-001d — equivalent own-inventory and
     * brokered deals producing identical schedules — is true by construction rather than by
     * a test that happens to pass.
     */
    @Transactional
    public PlanWithSchedule applyTemplate(UUID dealId, UUID templateId) {
        requireSellingRole();
        UUID tenantId = TenantContext.require();
        Deal deal = requireDeal(tenantId, dealId);
        requireDealEditable(deal);

        PaymentPlanTemplate template = getTemplate(templateId);
        if (!template.isActive()) {
            throw ApiException.businessRule(
                    "That payment plan template has been archived and cannot be applied to a "
                            + "new deal",
                    Map.of("templateId", templateId.toString()));
        }
        requireTemplateAvailableToDeal(deal, template);

        PaymentSchedule schedule = generate(deal, template);
        Optional<CustomerPaymentPlan> existing = plans.findDraftForDeal(tenantId, dealId);

        return existing.isPresent()
                ? regenerate(deal, existing.get(), template, schedule)
                : create(deal, template, schedule);
    }

    /** The schedule on a deal: the live plan if there is one, otherwise the draft. */
    @Transactional(readOnly = true)
    public Optional<PlanWithSchedule> planFor(UUID dealId) {
        UUID tenantId = TenantContext.require();
        return plans.findActiveForDeal(tenantId, dealId)
                .or(() -> plans.findDraftForDeal(tenantId, dealId))
                .map(plan -> new PlanWithSchedule(plan,
                        installments.findAllForPlan(tenantId, plan.id())));
    }

    // ------------------------------------------------------------------ activation

    /**
     * Doc 18 section 5's "draft → active", performed by the system as the deal activates.
     *
     * <p>Joins the caller's transaction rather than starting its own. A plan marked active
     * beside a deal that rolled back would be a schedule nobody owes against a sale that
     * never happened, and the only way to make that impossible is to share the transaction.
     *
     * <p>Re-asserts R-PLAN-4 over the PERSISTED rows rather than trusting that generation
     * got it right. The two are not the same claim: generation was checked when it ran, and
     * what matters at activation is that the rows sitting in the database now still add up
     * to the net value the deal now carries. A discount edited after the schedule was
     * generated is exactly the case, and it is the one doc 18 lists as precondition ②.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PlanWithSchedule activateFor(Deal deal) {
        UUID tenantId = deal.tenantId();
        CustomerPaymentPlan plan = plans.findDraftForDeal(tenantId, deal.id())
                .orElseThrow(() -> ApiException.businessRule(
                        "This deal has no payment schedule yet; apply a payment plan before "
                                + "activating it",
                        Map.of("dealId", deal.id().toString())));

        PlanWithSchedule generated = new PlanWithSchedule(plan,
                installments.findAllForPlan(tenantId, plan.id()));
        requirePlanStillMatchesDeal(deal, generated);

        try {
            plan.activate();
        } catch (IllegalStateException e) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION, e.getMessage());
        }
        plan.recordActor(SecurityContext.require().userId(), false);
        CustomerPaymentPlan saved = plans.save(plan);

        audit.record(AuditAction.PAYMENT_PLAN_ACTIVATED, "CustomerPaymentPlan", saved.id(),
                Map.of("status", "draft"),
                Map.of("status", saved.status().code(),
                        "dealId", deal.id().toString(),
                        "expectedTotal", generated.expectedTotal().toPlainString()), null);

        return new PlanWithSchedule(saved, generated.installments());
    }

    /**
     * Doc 18 section 4, precondition ②: R-PLAN-4 holds, over what is actually stored.
     *
     * <p>Two separate checks because they fail for different reasons and a caller deserves
     * to know which. The plan's own net value can drift from the deal's when a discount is
     * added after the schedule is generated. The rows can drift from the plan only if
     * something wrote them outside the generator, which nothing does — and which is why the
     * check is worth keeping: the day it fires, it will be about code nobody expected.
     */
    private void requirePlanStillMatchesDeal(Deal deal, PlanWithSchedule generated) {
        Money planNet = generated.plan().netValue();
        if (planNet.compareTo(deal.netValue()) != 0) {
            throw new ApiException(ErrorCode.PLAN_INVARIANT_VIOLATION,
                    "This deal's value changed after its payment schedule was generated; "
                            + "regenerate the schedule before activating",
                    Map.of("dealNetValue", deal.netValue().toPlainString(),
                            "scheduleNetValue", planNet.toPlainString()));
        }

        Money rowTotal = generated.expectedTotal();
        if (rowTotal.compareTo(deal.netValue()) != 0) {
            throw new ApiException(ErrorCode.PLAN_INVARIANT_VIOLATION,
                    "The payment schedule does not add up to the deal's net value (R-PLAN-4)",
                    Map.of("dealNetValue", deal.netValue().toPlainString(),
                            "scheduleTotal", rowTotal.toPlainString(),
                            "installmentCount", generated.installments().size()));
        }
    }

    // ------------------------------------------------------------------ internals

    private PaymentSchedule generate(Deal deal, PaymentPlanTemplate template) {
        Unit unit = units.get(deal.unitId());
        Project project = projects.get(unit.projectId());

        PlanTerms terms;
        try {
            terms = template.termsFor(deal.netValue(), deal.dealDate(),
                    Optional.ofNullable(project.deliveryDate()));
            return PaymentScheduleGenerator.generate(terms);
        } catch (IllegalArgumentException e) {
            // The generator refuses terms it cannot honour — a down payment larger than the
            // net value, a delivery tranche that leaves nothing to finance. Those are the
            // agent's numbers being wrong, not the system's, so they read as a 422 naming
            // the figures rather than a stack trace.
            throw new ApiException(ErrorCode.PLAN_INVARIANT_VIOLATION, e.getMessage(),
                    Map.of("netValue", deal.netValue().toPlainString(),
                            "templateId", template.id().toString()));
        }
    }

    private PlanWithSchedule create(Deal deal, PaymentPlanTemplate template,
                                    PaymentSchedule schedule) {
        CustomerPaymentPlan plan = CustomerPaymentPlan.draftFrom(deal.tenantId(), deal.id(),
                template.id(), schedule, template.installmentCount(), template.frequency(),
                firstInstallmentDate(schedule));
        plan.recordActor(SecurityContext.require().userId(), true);

        CustomerPaymentPlan saved;
        try {
            saved = plans.saveAndFlush(plan);
        } catch (DataIntegrityViolationException alreadyGenerated) {
            // uniq_draft_plan_per_deal. Two agents generating a schedule for the same deal
            // at the same moment; the index decides, and the loser is told to look again
            // rather than being handed a second schedule nobody asked for.
            throw ApiException.conflict(
                    "A payment schedule for this deal was generated a moment ago; reload the "
                            + "deal to see it",
                    Map.of("dealId", deal.id().toString()));
        }
        List<Installment> rows = writeRows(deal, saved.id(), schedule);

        audit.recordCreation(AuditAction.PAYMENT_PLAN_GENERATED, "CustomerPaymentPlan",
                saved.id(), describe(deal, saved, schedule));
        return new PlanWithSchedule(saved, rows);
    }

    private PlanWithSchedule regenerate(Deal deal, CustomerPaymentPlan plan,
                                        PaymentPlanTemplate template, PaymentSchedule schedule) {
        Map<String, Object> before = describe(deal, plan, null);

        installments.deleteDraftRows(deal.tenantId(), plan.id());
        try {
            plan.regenerateFrom(template.id(), schedule, template.installmentCount(),
                    template.frequency(), firstInstallmentDate(schedule));
        } catch (IllegalStateException e) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION, e.getMessage());
        }
        plan.recordActor(SecurityContext.require().userId(), false);
        CustomerPaymentPlan saved = plans.saveAndFlush(plan);
        List<Installment> rows = writeRows(deal, saved.id(), schedule);

        audit.record(AuditAction.PAYMENT_PLAN_REGENERATED, "CustomerPaymentPlan", saved.id(),
                before, describe(deal, saved, schedule), null);
        return new PlanWithSchedule(saved, rows);
    }

    /**
     * Persists the generated rows unchanged.
     *
     * <p>Nothing is recomputed on the way in, and nothing may be: {@link Installment#from}
     * takes a generated row and copies it. Recomputing here would be the third place the
     * arithmetic lived.
     */
    private List<Installment> writeRows(Deal deal, UUID planId, PaymentSchedule schedule) {
        UUID actor = SecurityContext.require().userId();
        return schedule.rows().stream()
                .map(row -> {
                    Installment installment =
                            Installment.from(deal.tenantId(), deal.id(), planId, row);
                    installment.recordActor(actor, true);
                    return installments.save(installment);
                })
                .toList();
    }

    /**
     * The date stored as the plan's {@code first_due_date}.
     *
     * <p>Read off the generated rows rather than recomputed from the terms, so the column
     * and the schedule cannot disagree. It is the first financed installment's date, not the
     * down payment's: the down payment falls on the deal date by definition, and a column
     * that sometimes meant one and sometimes the other would be useless for the reminder
     * queries Epic 7 runs over it.
     */
    private static LocalDate firstInstallmentDate(PaymentSchedule schedule) {
        return schedule.rowsOfKind(InstallmentKind.INSTALLMENT).stream()
                .findFirst()
                .map(PaymentSchedule.Row::dueDate)
                .orElseGet(schedule::lastDueDate);
    }

    private Deal requireDeal(UUID tenantId, UUID dealId) {
        Deal deal = deals.findByTenantIdAndId(tenantId, dealId)
                .orElseThrow(() -> ApiException.notFound("Deal"));
        authorization.requireRecordVisible(deal.agentUserId(), deal.branchId(), "Deal");
        return deal;
    }

    private static void requireDealEditable(Deal deal) {
        if (!deal.status().isEditable()) {
            throw new ApiException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                    "A payment schedule can only be generated while the deal is a draft; this "
                            + "one is " + deal.status().code(),
                    Map.of("dealStatus", deal.status().code()));
        }
    }

    /** A project-scoped template belongs to that project and to no other. */
    private void requireTemplateAvailableToDeal(Deal deal, PaymentPlanTemplate template) {
        if (template.projectId() == null) {
            return;
        }
        UUID projectId = units.get(deal.unitId()).projectId();
        if (!template.projectId().equals(projectId)) {
            throw ApiException.businessRule(
                    "That payment plan template belongs to a different project",
                    Map.of("templateProjectId", template.projectId().toString(),
                            "dealProjectId", projectId.toString()));
        }
    }

    private PaymentPlanTemplate saveTemplateExclusive(PaymentPlanTemplate template, String name) {
        try {
            return templates.saveAndFlush(template);
        } catch (DataIntegrityViolationException duplicateName) {
            // uq_plan_templates_name_per_tenant. Reported rather than silently accepted,
            // because two templates called "Standard 10%" is how an agent picks the wrong one.
            throw ApiException.conflict(
                    "This tenant already has a payment plan template called '" + name.trim() + "'",
                    Map.of("name", name.trim()));
        }
    }

    private void requireTemplateAdministration() {
        authorization.requireAnyRole(Role.OPERATIONS, Role.OWNER, Role.PLATFORM_ADMIN);
    }

    private void requireSellingRole() {
        authorization.requireAnyRole(Role.SALES_AGENT, Role.TEAM_LEADER, Role.BRANCH_MANAGER,
                Role.OPERATIONS, Role.OWNER, Role.PLATFORM_ADMIN);
    }

    private static Map<String, Object> describe(PaymentPlanTemplate template) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("name", template.name());
        described.put("downPaymentType", template.downPaymentType().code());
        described.put("deliveryPercent", template.deliveryPaymentPercent().toString());
        described.put("installmentCount", template.installmentCount());
        described.put("frequency", template.frequency().name().toLowerCase(Locale.ROOT));
        described.put("active", template.isActive());
        return described;
    }

    private static Map<String, Object> describe(Deal deal, CustomerPaymentPlan plan,
                                                PaymentSchedule schedule) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("dealId", deal.id().toString());
        described.put("version", plan.version());
        described.put("netValue", plan.netValue().toPlainString());
        described.put("downPayment", plan.downPaymentAmount().toPlainString());
        described.put("delivery", plan.deliveryPaymentAmount().toPlainString());
        described.put("financed", plan.financedAmount().toPlainString());
        described.put("installmentCount", plan.installmentCount());
        if (schedule != null) {
            described.put("rows", schedule.rows().size());
            described.put("scheduleTotal", schedule.total().toPlainString());
        }
        return described;
    }
}
