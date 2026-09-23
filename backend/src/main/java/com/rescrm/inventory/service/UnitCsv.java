package com.rescrm.inventory.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A small, strict CSV reader for the unit import (E3-S3).
 *
 * <p>Hand-written rather than pulled from a library, and the reason is the dependency budget
 * rather than pride: the format this accepts is one the operator's spreadsheet exports, and
 * the rules below are short enough to read in full. It handles quoted fields, embedded commas
 * and newlines, and doubled quotes — which is the part people usually get wrong, because an
 * Arabic address with a comma in it is not an edge case here, it is Tuesday.
 *
 * <p>What it deliberately does not do: guess the delimiter, skip malformed rows silently, or
 * trim a BOM into a column name without saying so. A file it cannot parse is an error the
 * operator sees, not a file that imports three of its hundred rows.
 */
final class UnitCsv {

    private UnitCsv() {
    }

    /** One parsed row, keyed by lower-cased header name, with its line number in the file. */
    record Row(int rowNumber, Map<String, String> values) {

        String get(String column) {
            String value = values.get(column);
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    static final class MalformedCsvException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        MalformedCsvException(String message) {
            super(message);
        }
    }

    /**
     * Parses the whole file into rows.
     *
     * @throws MalformedCsvException when the header is missing or a row's column count does
     *                               not match it — a misaligned row imports the wrong values
     *                               into the right columns, which is worse than not importing
     */
    static List<Row> parse(String content) {
        if (content == null || content.isBlank()) {
            throw new MalformedCsvException("The file is empty");
        }

        List<List<String>> records = split(stripByteOrderMark(content));
        if (records.isEmpty()) {
            throw new MalformedCsvException("The file is empty");
        }

        List<String> header = records.get(0).stream()
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .toList();
        if (header.stream().allMatch(String::isBlank)) {
            throw new MalformedCsvException("The first line must be a header row");
        }

        List<Row> rows = new ArrayList<>();
        for (int index = 1; index < records.size(); index++) {
            List<String> fields = records.get(index);
            if (fields.size() == 1 && fields.get(0).isBlank()) {
                continue;
            }
            // The line number the operator sees: header is line 1, so the first data row is 2.
            int rowNumber = index + 1;
            if (fields.size() != header.size()) {
                throw new MalformedCsvException("Row " + rowNumber + " has " + fields.size()
                        + " columns but the header has " + header.size());
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int column = 0; column < header.size(); column++) {
                values.put(header.get(column), fields.get(column));
            }
            rows.add(new Row(rowNumber, values));
        }
        return rows;
    }

    static List<String> headerOf(String content) {
        List<List<String>> records = split(stripByteOrderMark(content));
        return records.isEmpty() ? List.of() : records.get(0).stream()
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .toList();
    }

    private static List<List<String>> split(String content) {
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);

            if (inQuotes) {
                if (character == '"') {
                    // A doubled quote inside a quoted field is one literal quote.
                    if (index + 1 < content.length() && content.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(character);
                }
                continue;
            }

            switch (character) {
                case '"' -> inQuotes = true;
                case ',' -> {
                    current.add(field.toString());
                    field.setLength(0);
                }
                case '\r' -> {
                    // Swallowed: a CRLF file must not produce a trailing carriage return in
                    // every last column, which then fails every numeric parse in the file.
                }
                case '\n' -> {
                    current.add(field.toString());
                    field.setLength(0);
                    records.add(current);
                    current = new ArrayList<>();
                }
                default -> field.append(character);
            }
        }

        if (inQuotes) {
            throw new MalformedCsvException("The file ends inside a quoted value; a quote is "
                    + "unclosed");
        }
        current.add(field.toString());
        records.add(current);

        // A trailing newline produces one empty record; dropping it here keeps the row
        // numbering honest for everything above.
        if (records.size() > 1) {
            List<String> last = records.get(records.size() - 1);
            if (last.size() == 1 && last.get(0).isBlank()) {
                records.remove(records.size() - 1);
            }
        }
        return records;
    }

    private static String stripByteOrderMark(String content) {
        return content.startsWith("﻿") ? content.substring(1) : content;
    }
}
