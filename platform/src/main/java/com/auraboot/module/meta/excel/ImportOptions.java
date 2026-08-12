package com.auraboot.module.meta.excel;

import lombok.Data;

/**
 * Options controlling Excel import behavior.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Data
public class ImportOptions {

    /** When true, continue importing rows even if some fail. */
    private boolean skipErrors = false;

    /** When true, validate and parse only; do not persist any data. */
    private boolean dryRun = false;

    /** Date format pattern used when parsing date cells as strings. */
    private String dateFormat = "yyyy-MM-dd";

    /** INSERT creates new rows; UPDATE changes matching rows and never creates. */
    private String importMode = "insert";

    /** Server-whitelisted field used to find the row in UPDATE mode. */
    private String matchKey;
}
