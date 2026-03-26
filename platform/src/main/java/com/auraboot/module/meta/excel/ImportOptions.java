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

    /**
     * Field code used as the match key for UPSERT mode.
     * When set, existing records with a matching key value are UPDATED;
     * new records are CREATED. When null, all rows are INSERT only.
     */
    private String upsertKey;
}
