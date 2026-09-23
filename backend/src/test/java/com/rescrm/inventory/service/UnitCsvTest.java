package com.rescrm.inventory.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CSV reader behind the unit import (E3-S3).
 *
 * <p>The awkward cases are the point. An Arabic address containing a comma, a price written
 * with thousands separators, a file exported from Excel with a byte-order mark and CRLF line
 * endings — none of these are edge cases in this market, and a reader that mangles them turns
 * a hundred good rows into a hundred support tickets.
 */
@DisplayName("Unit CSV")
class UnitCsvTest {

    @Test
    @DisplayName("reads a plain file, numbering rows as the operator sees them")
    void reads_a_plain_file() {
        List<UnitCsv.Row> rows = UnitCsv.parse("""
                code,type,list_price
                A-101,apartment,2500000
                A-102,villa,9000000
                """);

        assertThat(rows).hasSize(2);
        // The header is line 1, so the first data row is 2 — the number in the error report
        // has to match the number in their spreadsheet or it is worse than no number.
        assertThat(rows.get(0).rowNumber()).isEqualTo(2);
        assertThat(rows.get(1).rowNumber()).isEqualTo(3);
        assertThat(rows.get(0).get("code")).isEqualTo("A-101");
        assertThat(rows.get(1).get("type")).isEqualTo("villa");
    }

    @Test
    @DisplayName("is case-insensitive about header names and trims them")
    void header_is_normalised() {
        List<UnitCsv.Row> rows = UnitCsv.parse("  Code , List_Price \nA-101,2500000\n");

        assertThat(rows.get(0).get("code")).isEqualTo("A-101");
        assertThat(rows.get(0).get("list_price")).isEqualTo("2500000");
    }

    @Test
    @DisplayName("keeps a comma inside a quoted value")
    void quoted_commas_survive() {
        List<UnitCsv.Row> rows = UnitCsv.parse(
                "code,view,list_price\nA-101,\"sea, partial\",2500000\n");

        assertThat(rows.get(0).get("view")).isEqualTo("sea, partial");
    }

    @Test
    @DisplayName("keeps a doubled quote as one literal quote")
    void doubled_quotes_survive() {
        List<UnitCsv.Row> rows = UnitCsv.parse(
                "code,view,list_price\nA-101,\"the \"\"garden\"\" side\",2500000\n");

        assertThat(rows.get(0).get("view")).isEqualTo("the \"garden\" side");
    }

    @Test
    @DisplayName("keeps a newline inside a quoted value")
    void quoted_newlines_survive() {
        List<UnitCsv.Row> rows = UnitCsv.parse(
                "code,view,list_price\nA-101,\"line one\nline two\",2500000\n");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("view")).isEqualTo("line one\nline two");
    }

    @Test
    @DisplayName("handles CRLF without leaving a carriage return in the last column")
    void crlf_is_handled() {
        List<UnitCsv.Row> rows = UnitCsv.parse("code,list_price\r\nA-101,2500000\r\n");

        // Left in, this breaks every numeric parse in the file and looks like bad data.
        assertThat(rows.get(0).get("list_price")).isEqualTo("2500000");
    }

    @Test
    @DisplayName("strips a byte-order mark rather than hiding it in the first column name")
    void byte_order_mark_is_stripped() {
        List<UnitCsv.Row> rows = UnitCsv.parse("﻿code,list_price\nA-101,2500000\n");

        assertThat(rows.get(0).get("code")).isEqualTo("A-101");
    }

    @Test
    @DisplayName("keeps Arabic text intact")
    void arabic_survives() {
        List<UnitCsv.Row> rows = UnitCsv.parse(
                "code,view,list_price\nA-101,\"إطلالة على البحر، جزئية\",2500000\n");

        assertThat(rows.get(0).get("view")).isEqualTo("إطلالة على البحر، جزئية");
    }

    @Test
    @DisplayName("treats a blank cell as absent rather than as an empty string")
    void blank_cells_are_absent() {
        List<UnitCsv.Row> rows = UnitCsv.parse("code,type,list_price\nA-101,,2500000\n");

        assertThat(rows.get(0).get("type")).isNull();
    }

    @Test
    @DisplayName("skips a blank line without shifting the row numbers after it")
    void blank_lines_are_skipped() {
        List<UnitCsv.Row> rows = UnitCsv.parse(
                "code,list_price\nA-101,2500000\n\nA-103,3000000\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).rowNumber())
                .as("row 4 in the file is row 4 in the report")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("refuses a row whose column count does not match the header")
    void misaligned_rows_are_refused() {
        // A misaligned row imports the wrong values into the right columns, which is worse
        // than not importing: it looks like data.
        assertThatThrownBy(() -> UnitCsv.parse(
                "code,type,list_price\nA-101,apartment\n"))
                .isInstanceOf(UnitCsv.MalformedCsvException.class)
                .hasMessageContaining("Row 2");
    }

    @Test
    @DisplayName("refuses a file that ends inside a quoted value")
    void unclosed_quote_is_refused() {
        assertThatThrownBy(() -> UnitCsv.parse("code,view\nA-101,\"unfinished\n"))
                .isInstanceOf(UnitCsv.MalformedCsvException.class)
                .hasMessageContaining("unclosed");
    }

    @Test
    @DisplayName("refuses an empty file")
    void empty_file_is_refused() {
        assertThatThrownBy(() -> UnitCsv.parse("   "))
                .isInstanceOf(UnitCsv.MalformedCsvException.class);
        assertThatThrownBy(() -> UnitCsv.parse(null))
                .isInstanceOf(UnitCsv.MalformedCsvException.class);
    }

    @Test
    @DisplayName("a header with no data rows parses to nothing, not to an error")
    void header_only_is_empty() {
        assertThat(UnitCsv.parse("code,list_price\n")).isEmpty();
    }
}
