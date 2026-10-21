package com.auraboot.framework.plugin.extension;

import java.util.List;
import java.util.Map;

/** One database page and the count of all matching, authorized records. */
public record DataPage(List<Map<String, Object>> records, long total, int page, int size) {
    public DataPage {
        records = List.copyOf(records);
        if (page < 1 || size < 1 || size > DataPageQuery.MAX_SIZE
                || records.size() > size || total < records.size()) {
            throw new IllegalArgumentException("Invalid database page result");
        }
    }
}
