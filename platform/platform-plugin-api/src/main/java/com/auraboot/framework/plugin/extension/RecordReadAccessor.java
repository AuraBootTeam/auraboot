package com.auraboot.framework.plugin.extension;

import java.util.Map;

/** Tenant-bound lookup capability with no mutation operations. */
public interface RecordReadAccessor {
    Map<String,Object> getById(String modelCode, String recordId);
    DataPage queryPage(String modelCode, DataPageQuery query);
}
