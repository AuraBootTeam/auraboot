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
        // Formula-injection neutralization: prefix a single quote when the cell
        // starts with a spreadsheet formula trigger.
        if (!str.isEmpty()) {
            char first = str.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@'
                    || first == '\t' || first == '\r') {
                str = "'" + str;
            }
        }
        // RFC-4180 quoting: quote when the value contains a delimiter, quote,
        // newline, or the injected leading single quote.
        if (str.contains(",") || str.contains("\"") || str.contains("\n") || str.contains("'")) {
            return "\"" + str.replace("\"", "\"\"") + "\"";
        }
        return str;
    }
}
