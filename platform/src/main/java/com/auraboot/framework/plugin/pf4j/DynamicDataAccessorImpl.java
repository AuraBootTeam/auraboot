package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.DataAccessErrorCode;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.DataAccessorException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implementation of DataAccessor that delegates to DynamicDataService.
 * Provides plugin command handlers with controlled access to dynamic entity data.
 *
 * <p>Every operation consumes the permit-plan context already opened by the command pipeline (or
 * reconstructed at the async task boundary). Re-deciding authorization here, on a different axis
 * and without knowing what the boundary ruled, is what caused the 2026-07-22 production incident.
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
        return withCommandAuthority(() -> dynamicDataService.create(modelCode, mutableCopy(data)));
    }

    @Override
    public Optional<Map<String, Object>> tryCreate(String modelCode, Map<String, Object> data) {
        log.debug("Plugin DataAccessor: tryCreate({}, {} fields)", modelCode, data != null ? data.size() : 0);
        try {
            return Optional.of(withCommandAuthority(
                    () -> dynamicDataService.create(modelCode, mutableCopy(data))));
        } catch (RuntimeException error) {
            if (!IdempotentCreateSupport.isUniqueViolation(error)) throw error;
            log.debug("Plugin DataAccessor: tryCreate hit unique violation for model={}", modelCode);
            return Optional.empty();
        }
    }

    @Override
    public Map<String, Object> update(String modelCode, String recordId, Map<String, Object> data) {
        log.debug("Plugin DataAccessor: update({}, {})", modelCode, recordId);
        return withCommandAuthority(() -> dynamicDataService.update(modelCode, recordId, mutableCopy(data)));
    }

    @Override
    public boolean compareAndSet(String modelCode,
                                 String recordId,
                                 String fieldCode,
                                 Object expectedValue,
                                 Object nextValue) {
        log.debug("Plugin DataAccessor: compareAndSet({}, {}, {})", modelCode, recordId, fieldCode);
        return withCommandAuthority(() -> dynamicDataService.compareAndSet(
                modelCode, recordId, fieldCode, expectedValue, nextValue));
    }

    @Override
    public boolean compareAndSet(String modelCode,
                                 String recordId,
                                 String fieldCode,
                                 Object expectedValue,
                                 Map<String, Object> nextValues) {
        log.debug("Plugin DataAccessor: compareAndSet({}, {}, {})", modelCode, recordId, fieldCode);
        return withCommandAuthority(() -> dynamicDataService.compareAndSet(
                modelCode, recordId, fieldCode, expectedValue, mutableCopy(nextValues)));
    }

    @Override
    public List<Map<String, Object>> batchCreate(String modelCode, List<Map<String, Object>> dataList) {
        log.debug("Plugin DataAccessor: batchCreate({}, {} records)", modelCode, dataList != null ? dataList.size() : 0);
        List<Map<String, Object>> safeData = mutableCopies(dataList);
        var response = withCommandAuthority(() -> dynamicDataService.batchCreate(modelCode, safeData));
        if (response != null && response.getSuccessItems() != null) {
            return response.getSuccessItems();
        }
        return dataList != null ? dataList : List.of();
    }

    @Override
    public List<Map<String, Object>> bulkCreate(String modelCode, List<Map<String, Object>> dataList) {
        log.debug("Plugin DataAccessor: bulkCreate({}, {} records)", modelCode, dataList != null ? dataList.size() : 0);
        return withCommandAuthority(() -> dynamicDataService.bulkCreate(modelCode, mutableCopies(dataList)));
    }

    @Override
    public void delete(String modelCode, String recordId) {
        log.debug("Plugin DataAccessor: delete({}, {})", modelCode, recordId);
        withCommandAuthority(() -> { dynamicDataService.delete(modelCode, recordId); return null; });
    }

    @Override
    public void batchDelete(String modelCode, Collection<String> recordIds) {
        List<String> ids = recordIds == null
                ? List.of()
                : recordIds.stream()
                        .filter(java.util.Objects::nonNull)
                        .filter(id -> !id.isBlank())
                        .distinct()
                        .toList();
        if (ids.isEmpty()) {
            return;
        }
        log.debug("Plugin DataAccessor: batchDelete({}, {} records)", modelCode, ids.size());
        withCommandAuthority(() -> {
            dynamicDataService.batchDelete(modelCode, ids);
            return null;
        });
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

    /** The authoritative permit context is installed outside this adapter. */
    private <T> T withCommandAuthority(java.util.function.Supplier<T> operation) {
        try {
            return operation.get();
        } catch (AccessDeniedException | com.auraboot.framework.exception.PermissionDeniedException denied) {
            // CATCH: public plugin boundary — translate host authorization types
            // into the stable, transport-neutral DataAccessor contract.
            throw new DataAccessorException(DataAccessErrorCode.PERMISSION_DENIED, denied);
        }
    }

    private static Map<String, Object> mutableCopy(Map<String, Object> data) {
        return data == null ? null : new LinkedHashMap<>(data);
    }

    private static List<Map<String, Object>> mutableCopies(List<Map<String, Object>> dataList) {
        if (dataList == null) {
            return null;
        }
        return dataList.stream().map(DynamicDataAccessorImpl::mutableCopy).toList();
    }
}
