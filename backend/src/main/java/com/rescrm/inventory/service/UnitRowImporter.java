package com.rescrm.inventory.service;

import com.rescrm.platform.errors.ApiException;
import com.rescrm.platform.errors.ErrorCode;
import com.rescrm.platform.money.CurrencyCode;
import com.rescrm.platform.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Imports one CSV row in a transaction of its own (E3-S3).
 *
 * <p>A separate bean rather than a method on {@link UnitImportService}, and that is the whole
 * reason it exists. {@code REQUIRES_NEW} is applied by a Spring proxy, and a proxy cannot
 * intercept a call an object makes to itself — so a self-invoked {@code importRow} would
 * quietly share the caller's transaction, and one bad row at the end would roll back the
 * ninety-nine good ones at commit. The API would report success per row and the database
 * would be empty. Crossing a bean boundary is what makes the per-row isolation real.
 */
@Service
class UnitRowImporter {

    static final String COLUMN_CODE = "code";
    static final String COLUMN_TYPE = "type";
    static final String COLUMN_AREA = "area_sqm";
    static final String COLUMN_FLOOR = "floor";
    static final String COLUMN_VIEW = "view";
    static final String COLUMN_PRICE = "list_price";
    static final String COLUMN_PHASE = "phase";

    private final UnitService units;

    UnitRowImporter(UnitService units) {
        this.units = units;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID importRow(UUID projectId, UnitCsv.Row row, Map<String, UUID> phasesByName) {
        String code = row.get(COLUMN_CODE);
        if (code == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A unit code is required");
        }

        Money listPrice = parsePrice(row.get(COLUMN_PRICE));
        BigDecimal area = parseArea(row.get(COLUMN_AREA));
        UUID phaseId = resolvePhase(row.get(COLUMN_PHASE), phasesByName);

        return units.createWithin(projectId, phaseId, code, row.get(COLUMN_TYPE), area,
                row.get(COLUMN_FLOOR), row.get(COLUMN_VIEW), listPrice).id();
    }

    // ------------------------------------------------------------------ parsing

    private static Money parsePrice(String raw) {
        if (raw == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A list price is required");
        }
        // Thousands separators and a currency suffix are what a spreadsheet exports when the
        // column is formatted as currency; rejecting them would make the common export
        // unusable and send the operator back to reformat a hundred rows by hand.
        String cleaned = raw.replace(",", "").replace("EGP", "").trim();
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "'" + raw + "' is not a valid list price");
        }
        try {
            return Money.of(parsed, CurrencyCode.EGP);
        } catch (IllegalArgumentException | ArithmeticException e) {
            // Money refuses more than two decimal places rather than rounding. Passing the
            // reason through matters here: "2500000.005 has more than 2 decimal places" tells
            // the operator what to fix, where "invalid price" sends them hunting.
            throw new ApiException(ErrorCode.VALIDATION_FAILED, e.getMessage());
        }
    }

    private static BigDecimal parseArea(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.replace(",", "").trim());
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "'" + raw + "' is not a valid area");
        }
    }

    /**
     * Phases are named in the file, never identified by UUID: nobody types a UUID into a
     * spreadsheet. An unknown name is a row error rather than a phase created on the fly,
     * because a typo would otherwise produce a phase called "Phse 2" that nobody notices
     * until the delivery schedule is wrong.
     */
    private static UUID resolvePhase(String name, Map<String, UUID> phasesByName) {
        if (name == null) {
            return null;
        }
        UUID phaseId = phasesByName.get(name.trim().toLowerCase(Locale.ROOT));
        if (phaseId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "This project has no phase named '" + name + "'");
        }
        return phaseId;
    }
}
