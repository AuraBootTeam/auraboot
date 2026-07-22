package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.DataAccessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implementation of DataAccessor that delegates to DynamicDataService.
 * Provides plugin command handlers with controlled access to dynamic entity data.
 *
 * <p>Every operation runs under {@link #withCommandAuthority}: when the command boundary has
 * already authorized this caller for this command (DDR-2026-07-22), the handler's data access is
 * NOT re-projected through the caller's record-level read permission. Re-deciding authorization
 * here, on a different axis and without knowing what the boundary ruled, is what let a caller
 * authorized to RUN price sourcing have the sourcing's own bookkeeping write refused, silently
 * duplicating the row it failed to update (2026-07-22).
 *
 * <p>Absent that authority — no scope open — behaviour is exactly what it was: the caller's
 * projection applies. Commands that declare no permissions never open a scope, so they gain
 * nothing here.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@RequiredArgsConstructor
public class DynamicDataAccessorImpl implements DataAccessor {

    private final DynamicDataService dynamicDataService;

    @Override
    public Map<String, Object> getById(String modelCode, String recordId) {
        log.debug("Plugin DataAccessor: getById({}, {})", modelCode, recordId);
        return withCommandAuthority(() -> dynamicDataService.getById(modelCode, recordId));
    }

    @Override
    public List<Map<String, Object>> query(String modelCode, Map<String, Object> filters) {
        log.debug("Plugin DataAccessor: query({}, {})", modelCode, filters);

        List<QueryCondition> conditions = new ArrayList<>();
        if (filters != null) {
            for (Map.Entry<String, Object> entry : filters.entrySet()) {
                conditions.add(QueryCondition.builder()
                        .fieldName(entry.getKey())
                        .operator(QueryCondition.Operator.EQ)
                        .value(entry.getValue())
                        .build());
            }
        }

        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(10000)
                .conditions(conditions)
                .build();

        PaginationResult<Map<String, Object>> result =
                withCommandAuthority(() -> dynamicDataService.list(modelCode, request));
        return result.getRecords() != null ? result.getRecords() : List.of();
    }

    @Override
    public List<Map<String, Object>> queryIn(String modelCode, String fieldName, Collection<?> values) {
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName cannot be null or blank");
        }
        List<Object> queryValues = distinctNonNullValues(values);
        if (queryValues.isEmpty()) {
            return List.of();
        }

        log.debug("Plugin DataAccessor: queryIn({}, {}, {} values)", modelCode, fieldName, queryValues.size());

        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(10000)
                .conditions(List.of(QueryCondition.builder()
                        .fieldName(fieldName)
                        .operator(QueryCondition.Operator.IN)
                        .values(queryValues)
                        .build()))
                .build();

        PaginationResult<Map<String, Object>> result =
                withCommandAuthority(() -> dynamicDataService.list(modelCode, request));
        return result.getRecords() != null ? result.getRecords() : List.of();
    }

    private static List<Object> distinctNonNullValues(Collection<?> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Object> distinct = new LinkedHashSet<>();
        for (Object value : values) {
            if (value != null) {
                distinct.add(value);
            }
        }
        return List.copyOf(distinct);
    }

    @Override
    public Map<String, Object> create(String modelCode, Map<String, Object> data) {
        log.debug("Plugin DataAccessor: create({}, {} fields)", modelCode, data != null ? data.size() : 0);
        return withCommandAuthority(() -> dynamicDataService.create(modelCode, data));
    }

    @Override
    public Map<String, Object> update(String modelCode, String recordId, Map<String, Object> data) {
        log.debug("Plugin DataAccessor: update({}, {})", modelCode, recordId);
        return withCommandAuthority(() -> dynamicDataService.update(modelCode, recordId, data));
    }

    @Override
    public List<Map<String, Object>> batchCreate(String modelCode, List<Map<String, Object>> dataList) {
        log.debug("Plugin DataAccessor: batchCreate({}, {} records)", modelCode, dataList != null ? dataList.size() : 0);
        var response = withCommandAuthority(() -> dynamicDataService.batchCreate(modelCode, dataList));
        if (response != null && response.getSuccessItems() != null) {
            return response.getSuccessItems();
        }
        return dataList != null ? dataList : List.of();
    }

    @Override
    public List<Map<String, Object>> bulkCreate(String modelCode, List<Map<String, Object>> dataList) {
        log.debug("Plugin DataAccessor: bulkCreate({}, {} records)", modelCode, dataList != null ? dataList.size() : 0);
        return withCommandAuthority(() -> dynamicDataService.bulkCreate(modelCode, dataList));
    }

    @Override
    public void delete(String modelCode, String recordId) {
        log.debug("Plugin DataAccessor: delete({}, {})", modelCode, recordId);
        withCommandAuthority(() -> { dynamicDataService.delete(modelCode, recordId); return null; });
    }

    @Override
    public Optional<Long> incrementWithinCap(String modelCode,
                                             String recordId,
                                             String counterCode,
                                             long delta,
                                             String capCode) {
        log.debug("Plugin DataAccessor: incrementWithinCap({}, {}, {}, {})",
                modelCode, recordId, counterCode, delta);
        return withCommandAuthority(() -> dynamicDataService.incrementWithinCap(
                modelCode, recordId, counterCode, delta, capCode));
    }

    /**
     * Execute {@code operation} under the command boundary's authority when one is open.
     *
     * <p>The scope carries the permission the caller was already checked against, so the platform
     * reading and writing on that command's behalf is not the caller reading data — it is the
     * command doing what it was authorized to do. Tenant scoping and the recorded actor are
     * untouched; only the caller's record-level read projection stops being re-applied.
     */
    private <T> T withCommandAuthority(java.util.function.Supplier<T> operation) {
        if (!MetaContext.hasCommandAuthority()) {
            return operation.get();
        }
        return MetaContext.runWithoutDataPermission(operation);
    }
}
