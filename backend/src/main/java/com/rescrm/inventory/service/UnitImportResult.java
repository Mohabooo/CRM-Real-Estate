package com.rescrm.inventory.service;

import java.util.List;
import java.util.UUID;

/**
 * What a CSV import did, row by row (E3-S3).
 *
 * <p>The acceptance criterion is "3 invalid rows out of 100 → 97 imported, 3 reported with
 * reasons", and the shape of this record is that sentence. There is no all-or-nothing mode:
 * an operator loading a developer's price list at nine in the morning needs the 97 units, and
 * making them fix a typo in row 4 before anything lands is how people end up editing the
 * database by hand instead.
 */
public record UnitImportResult(int rowsRead, List<UUID> imported, List<RowError> errors) {

    /**
     * One row that did not import, and why.
     *
     * @param rowNumber the line in the file as the operator sees it, header included, so the
     *                  number in the report matches the number in their spreadsheet
     */
    public record RowError(int rowNumber, String code, String message) {
    }

    public int importedCount() {
        return imported.size();
    }

    public int errorCount() {
        return errors.size();
    }

    public boolean isCleanImport() {
        return errors.isEmpty();
    }
}
