package com.auraboot.framework.meta.service.executor;

import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;

import java.util.Map;

/**
 * Pluggable executor for a model data path.
 * Implementations must NOT bypass tenant/permission/audit pipeline — they should
 * either invoke existing services that carry the pipeline or apply the equivalent
 * safety filters before delegating to raw data access.
 *
 * Phase 1 executors: PhysicalModelExecutor (implicit, handled by DynamicDataServiceImpl
 * inline path), NamedQueryModelExecutor, SqlViewModelExecutor, EndpointModelExecutor.
 */
public interface ModelDataExecutor {

    /** Returns the source type value this executor handles (e.g. "namedQuery", "endpoint", "sqlView"). */
    String sourceType();

    /** List records for the given model with pagination / filter / sort. */
    PaginationResult<Map<String, Object>> list(String modelCode, DynamicQueryRequest request);

    /** Retrieve a single record by primary-key value. Returns null if not found. */
    Map<String, Object> get(String modelCode, Object primaryKeyValue);
}
