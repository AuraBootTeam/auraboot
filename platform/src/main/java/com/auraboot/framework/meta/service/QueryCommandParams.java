package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.dto.SortField;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parameters of a record read, as the agent tool and the command executor both accept them.
 *
 * <p>A caller pages with {@code pageNum} (or {@code page}) and {@code pageSize}, searches with
 * {@code keyword} (or {@code search}), filters with {@code filters} (a list of
 * {@link QueryCondition}) and sorts with {@code sortFields}, or with {@code sortField} and
 * {@code sortOrder}. Every other parameter is passed on as an extra parameter.</p>
 *
 * <p>{@code strict} serves a caller that states its request exactly, such as the command API.
 * It refuses a malformed filter or sort order, a paging value that is not a number and any
 * parameter outside this contract, where the lenient reading drops them: a dropped filter
 * answers a filtered question with every record, and an unknown parameter was never a filter.</p>
 */
@Slf4j
public final class QueryCommandParams {

    /** The parameters this contract names. Anything else is an extra parameter. */
    public static final List<String> RESERVED = List.of("pageNum", "page", "pageSize", "keyword", "search",
            "filters", "sortField", "sortOrder", "sortFields", "recordPid");

    private static final TypeReference<List<QueryCondition>> CONDITIONS = new TypeReference<>() {};
    private static final TypeReference<List<SortField>> SORT_FIELDS = new TypeReference<>() {};

    private QueryCommandParams() {
    }

    /** The record a parameter names, or {@code null} when the read is a page. */
    public static String recordPid(Map<String, Object> params) {
        if (params == null) {
            return null;
        }
        Object recordPid = params.get("recordPid");
        return recordPid == null || recordPid.toString().isBlank() ? null : recordPid.toString();
    }

    public static DynamicQueryRequest toRequest(ObjectMapper objectMapper, Map<String, Object> params, boolean strict) {
        Map<String, Object> safe = params == null ? Map.of() : params;
        if (strict) {
            for (String key : safe.keySet()) {
                if (!RESERVED.contains(key)) {
                    throw invalid("Unknown query parameter '" + key + "'");
                }
            }
            requireNumber(safe, "pageNum");
            requireNumber(safe, "page");
            requireNumber(safe, "pageSize");
            for (String key : List.of("keyword", "search", "sortField")) {
                Object value = safe.get(key);
                if (value != null && !(value instanceof String)) {
                    throw invalid("'" + key + "' must be text");
                }
            }
            Object sortOrder = safe.get("sortOrder");
            if (sortOrder != null && !("asc".equalsIgnoreCase(String.valueOf(sortOrder))
                    || "desc".equalsIgnoreCase(String.valueOf(sortOrder)))) {
                throw invalid("'sortOrder' must be asc or desc");
            }
        }
        return DynamicQueryRequest.builder()
                .pageNum(pageNum(safe))
                .pageSize(pageSize(safe))
                .keyword(keyword(safe))
                .conditions(conditions(objectMapper, safe.get("filters"), strict))
                .sortFields(sortFields(objectMapper, safe, strict))
                .extraParams(extraParams(safe))
                .build();
    }

    private static void requireNumber(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value != null && !(value instanceof Number)) {
            throw invalid("'" + key + "' must be a number");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ResponseCode.BadParam, message);
    }

    private static Integer pageNum(Map<String, Object> params) {
        return params.get("pageNum") instanceof Number n ? n.intValue()
                : params.get("page") instanceof Number n ? n.intValue()
                : 1;
    }

    private static Integer pageSize(Map<String, Object> params) {
        return params.get("pageSize") instanceof Number n ? n.intValue() : 20;
    }

    private static String keyword(Map<String, Object> params) {
        return params.get("keyword") instanceof String s ? s
                : params.get("search") instanceof String s ? s
                : null;
    }

    private static List<QueryCondition> conditions(ObjectMapper objectMapper, Object rawFilters, boolean strict) {
        if (rawFilters == null) {
            return null;
        }
        List<QueryCondition> conditions;
        try {
            if (rawFilters instanceof String text) {
                if (text.isBlank()) {
                    return null;
                }
                conditions = objectMapper.readValue(text, CONDITIONS);
            } else {
                conditions = objectMapper.convertValue(rawFilters, CONDITIONS);
            }
        } catch (Exception e) {
            if (strict) {
                throw invalid("'filters' must be a list of query conditions");
            }
            log.warn("Ignoring invalid query filters: {}", rawFilters, e);
            return null;
        }
        return conditions == null || conditions.isEmpty() ? null : conditions;
    }

    private static List<SortField> sortFields(ObjectMapper objectMapper, Map<String, Object> params, boolean strict) {
        Object rawSortFields = params.get("sortFields");
        if (rawSortFields != null) {
            if (!(rawSortFields instanceof List<?> list)) {
                if (strict) {
                    throw invalid("'sortFields' must be a list of sort fields");
                }
            } else if (!list.isEmpty()) {
                try {
                    return objectMapper.convertValue(list, SORT_FIELDS);
                } catch (Exception e) {
                    if (strict) {
                        throw invalid("'sortFields' must be a list of sort fields");
                    }
                    log.warn("Ignoring invalid query sortFields: {}", rawSortFields, e);
                }
            }
        }

        if (!(params.get("sortField") instanceof String fieldName) || fieldName.isBlank()) {
            return null;
        }
        SortField.SortDirection direction = "asc".equalsIgnoreCase(String.valueOf(params.get("sortOrder")))
                ? SortField.SortDirection.ASC
                : SortField.SortDirection.DESC;
        return List.of(SortField.builder()
                .fieldName(fieldName)
                .direction(direction)
                .build());
    }

    private static Map<String, Object> extraParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> extra = new LinkedHashMap<>(params);
        extra.keySet().removeAll(RESERVED);
        return extra.isEmpty() ? Collections.emptyMap() : extra;
    }
}
