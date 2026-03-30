package com.auraboot.framework.meta.entity.payload;

import lombok.Data;

/**
 * Configuration for a page's data source.
 * Can be embedded in a PageSchema's blocks to specify a NamedQuery data source
 * instead of the default model-based table query.
 *
 * Usage in page DSL:
 * <pre>
 * {
 *   "dataSource": {
 *     "type": "namedQuery",
 *     "queryCode": "crm_customer_list",
 *     "version": null
 *   }
 * }
 * </pre>
 */
@Data
public class PageDataSourceConfig {

    /** Data source type: "table" (default) or "namedQuery" */
    private String type = "table";

    /** Named query code (when type = "namedQuery") */
    private String queryCode;

    /** Optional version (null = latest) */
    private Integer version;

    public boolean isNamedQuery() {
        return "namedQuery".equals(type);
    }
}
