package com.rescrm.inventory.service;

import com.rescrm.platform.audit.AuditAction;
import com.rescrm.platform.audit.AuditWriter;
import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.security.AuthorizationService;
import com.rescrm.platform.security.Role;
import com.rescrm.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Bulk unit creation from CSV (E3-S3).
 *
 * <p>The behaviour the story asks for — "3 invalid rows out of 100 → 97 imported, 3 reported
 * with reasons" — needs each row to succeed or fail on its own. {@link UnitRowImporter} gives
 * each one its own transaction; this class reads the file, checks permission once, collects
 * the outcomes and writes a single audit entry for the batch.
 *
 * <p>{@link Propagation#NOT_SUPPORTED} here is deliberate. If this method held a transaction,
 * every per-row transaction would be a nested one inside it, and a rollback of the outer at
 * the end would take the good rows with it — which is the exact failure the per-row design is
 * there to prevent.
 *
 * <p>Epic 10 owns the general import/export facility with its {@code /imports} resource and
 * status polling. This is the narrow synchronous version Epic 3 needs: one file, one project,
 * an answer in the response.
 */
@Service
public class UnitImportService {

    /** Above this the operator wants Epic 10's asynchronous import, not a hung request. */
    private static final int MAX_ROWS = 5_000;

    private final UnitRowImporter rowImporter;
    private final UnitService units;
    private final PhaseService phases;
    private final AuthorizationService authorization;
    private final AuditWriter audit;

    public UnitImportService(UnitRowImporter rowImporter, UnitService units, PhaseService phases,
                             AuthorizationService authorization, AuditWriter audit) {
        this.rowImporter = rowImporter;
        this.units = units;
        this.phases = phases;
        this.authorization = authorization;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UnitImportResult importUnits(UUID projectId, String csv) {
        authorization.requireAnyRole(Role.OWNER, Role.OPERATIONS, Role.PLATFORM_ADMIN);
        UUID tenantId = TenantContext.require();
        units.requireProjectInTenant(tenantId, projectId);

        List<UnitCsv.Row> rows = parse(csv);
        requireUsableHeader(csv);
        if (rows.size() > MAX_ROWS) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "This import accepts at most " + MAX_ROWS + " rows at a time",
                    Map.of("rows", rows.size(), "limit", MAX_ROWS));
        }

        Map<String, UUID> phasesByName = phaseLookup(projectId);
        List<UUID> imported = new ArrayList<>();
        List<UnitImportResult.RowError> errors = new ArrayList<>();

        for (UnitCsv.Row row : rows) {
            try {
                imported.add(rowImporter.importRow(projectId, row, phasesByName));
            } catch (ApiException e) {
                errors.add(new UnitImportResult.RowError(row.rowNumber(), e.code().name(),
                        e.getMessage()));
            } catch (RuntimeException e) {
                // Anything unexpected is still one row's problem. Letting it escape would
                // abandon every row after it, which is what this design exists to avoid.
                errors.add(new UnitImportResult.RowError(row.rowNumber(),
                        ErrorCode.INTERNAL_ERROR.name(),
                        "The row could not be imported: " + rootMessage(e)));
            }
        }

        recordBatch(projectId, rows.size(), imported, errors);
        return new UnitImportResult(rows.size(), List.copyOf(imported), List.copyOf(errors));
    }

    // ------------------------------------------------------------------ internals

    private static List<UnitCsv.Row> parse(String csv) {
        try {
            return UnitCsv.parse(csv);
        } catch (UnitCsv.MalformedCsvException e) {
            // A file that cannot be parsed is not a hundred bad rows; it is one bad file, and
            // reporting it as a hundred row errors would bury the actual problem.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage(),
                    Map.of("expectedColumns", List.of(
                            UnitRowImporter.COLUMN_CODE, UnitRowImporter.COLUMN_PRICE,
                            UnitRowImporter.COLUMN_TYPE, UnitRowImporter.COLUMN_AREA,
                            UnitRowImporter.COLUMN_FLOOR, UnitRowImporter.COLUMN_VIEW,
                            UnitRowImporter.COLUMN_PHASE)));
        }
    }

    private static void requireUsableHeader(String csv) {
        List<String> header = UnitCsv.headerOf(csv);
        if (!header.contains(UnitRowImporter.COLUMN_CODE)
                || !header.contains(UnitRowImporter.COLUMN_PRICE)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "The file must have at least a '" + UnitRowImporter.COLUMN_CODE + "' and a '"
                            + UnitRowImporter.COLUMN_PRICE + "' column",
                    Map.of("header", header));
        }
    }

    /**
     * One entry for the batch, not one per unit.
     *
     * <p>A hundred identical {@code UNIT_CREATED} entries would bury the trail they are meant
     * to make readable. The batch entry carries the counts and the ids, which is what somebody
     * reconstructing "where did these units come from" actually needs.
     */
    private void recordBatch(UUID projectId, int rowsRead, List<UUID> imported,
                             List<UnitImportResult.RowError> errors) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("projectId", projectId.toString());
        summary.put("rowsRead", rowsRead);
        summary.put("imported", imported.size());
        summary.put("failed", errors.size());
        summary.put("unitIds", imported.stream().map(UUID::toString).toList());
        audit.recordCreation(AuditAction.UNITS_IMPORTED, "Project", projectId, summary);
    }

    private Map<String, UUID> phaseLookup(UUID projectId) {
        Map<String, UUID> lookup = new LinkedHashMap<>();
        phases.listForProject(projectId).forEach(phase ->
                lookup.put(phase.name().toLowerCase(Locale.ROOT), phase.id()));
        return lookup;
    }

    private static String rootMessage(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
