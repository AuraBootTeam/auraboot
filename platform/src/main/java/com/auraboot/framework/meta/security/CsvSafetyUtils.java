package com.auraboot.framework.meta.security;

/**
 * Centralized CSV safety utilities.
 *
 * <p>Neutralizes CSV / formula injection (a.k.a. CSV injection): a cell whose
 * first character is one of {@code = + - @ \t \r} is interpreted as a formula by
 * Excel / LibreOffice / Google Sheets when the exported file is opened, which can
 * exfiltrate data or trigger command execution. Such cells are prefixed with a
 * single quote to force text interpretation, then RFC-4180 quoted as needed.
 *
 * <p>Use this for every CSV export path so the escaping stays consistent (see
 * {@code ExportAsyncTaskExecutor} and {@code DynamicDataServiceImpl.exportAsCsv}).
 */
public final class CsvSafetyUtils {

    private CsvSafetyUtils() {
        // utility class
    }

    /**
     * Escape a single CSV cell: neutralize formula injection then apply RFC-4180
     * quoting.
     *
     * @param val the raw cell value (may be null)
     * @return the safe CSV cell text (never null)
     */
    public static String escapeCsvCell(Object val) {
        if (val == null) {
            return "";
        }
        String str = val.toString();
        // Importers may discard whitespace or invisible prefixes before interpreting a formula.
        if (hasFormulaPrefix(str)) str = "'" + str;
        // RFC-4180 quoting: quote when the value contains a delimiter, quote,
        // newline, or the injected leading single quote.
        if (str.contains(",") || str.contains("\"") || str.contains("\n") || str.contains("\r") || str.contains("'")) {
            return "\"" + str.replace("\"", "\"\"") + "\"";
        }
        return str;
    }

    private static boolean hasFormulaPrefix(String value) {
        int offset=0;boolean control=false;
        while(offset<value.length()) {
            int point=value.codePointAt(offset);
            if(Character.isISOControl(point)) control=true;
            if(!Character.isWhitespace(point) && !Character.isSpaceChar(point)
                && !Character.isISOControl(point) && point!=0xFEFF && point!=0x200B) break;
            offset+=Character.charCount(point);
        }
        if(control) return true;
        if(offset==value.length()) return false;
        char first=value.charAt(offset);
        return first=='=' || first=='+' || first=='-' || first=='@';
    }
}
