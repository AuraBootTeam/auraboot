package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.DataAccessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Implementation of DataAccessor that delegates to DynamicDataService.
 * Provides plugin command handlers with controlled access to dynamic entity data.
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
        return dynamicDataService.getById(modelCode, recordId);
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

        PaginationResult<Map<String, Object>> result = dynamicDataService.list(modelCode, request);
        return result.getRecords() != null ? result.getRecords() : List.of();
    }

    @Override
    public Map<String, Object> create(String modelCode, Map<String, Object> data) {
        log.debug("Plugin DataAccessor: create({}, {} fields)", modelCode, data != null ? data.size() : 0);
        return dynamicDataService.create(modelCode, data);
    }

    @Override
    public Map<String, Object> update(String modelCode, String recordId, Map<String, Object> data) {
        log.debug("Plugin DataAccessor: update({}, {})", modelCode, recordId);
        return dynamicDataService.update(modelCode, recordId, data);
    }

    @Override
    public List<Map<String, Object>> batchCreate(String modelCode, List<Map<String, Object>> dataList) {
        log.debug("Plugin DataAccessor: batchCreate({}, {} records)", modelCode, dataList != null ? dataList.size() : 0);
        var response = dynamicDataService.batchCreate(modelCode, dataList);
        if (response != null && response.getSuccessItems() != null) {
            return response.getSuccessItems();
        }
        return dataList != null ? dataList : List.of();
    }

    @Override
    public void delete(String modelCode, String recordId) {
        log.debug("Plugin DataAccessor: delete({}, {})", modelCode, recordId);
        dynamicDataService.delete(modelCode, recordId);
    }
}
