package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.QueryBuilderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Range filters on datetime columns must bind a real temporal value: the
 * dynamic-list API receives full ISO-8601 instants ("…T16:00:00.000Z") from
 * HTTP clients, and binding the raw string made Postgres reject
 * {@code timestamptz >= text} with HTTP 500 (edu mini-program records page,
 * 2026-09-21).
 */
@DisplayName("QueryBuilder datetime range filters")
class QueryBuilderDateTimeRangeTest {

    private QueryBuilderService queryBuilderService = new QueryBuilderServiceImpl(null);

    private ModelDefinition model() {
        return ModelDefinition.builder()
                .code("test_eval")
                .tableName("mt_test_eval")
                .fields(List.of(FieldDefinition.builder()
                        .code("occurred_at")
                        .columnName("occurred_at")
                        .dataType("datetime")
                        .build()))
                .build();
    }

    private Map<String, Object> paramsFor(String value) {
        QueryBuilderService.QueryBuilder qb = queryBuilderService.buildConditionQuery(model(), List.of(
                QueryCondition.builder()
                        .fieldName("occurred_at")
                        .operator(QueryCondition.Operator.GE)
                        .value(value)
                        .build()));
        return qb.getParameterMap();
    }

    @Test
    @DisplayName("ISO instant with Z binds as a Timestamp")
    void isoInstantBindsAsTimestamp() {
        Map<String, Object> params = paramsFor("2026-09-14T16:00:00.000Z");
        Timestamp ts = params.values().stream()
                .filter(Timestamp.class::isInstance)
                .map(Timestamp.class::cast)
                .findFirst()
                .orElse(null);
        assertEquals(Instant.parse("2026-09-14T16:00:00Z"), ts.toInstant());
    }

    @Test
    @DisplayName("ISO offset form binds as a Timestamp")
    void isoOffsetBindsAsTimestamp() {
        Map<String, Object> params = paramsFor("2026-09-14T16:00:00+00:00");
        Timestamp ts = params.values().stream()
                .filter(Timestamp.class::isInstance)
                .map(Timestamp.class::cast)
                .findFirst()
                .orElse(null);
        assertEquals(Instant.parse("2026-09-14T16:00:00Z"), ts.toInstant());
    }

    @Test
    @DisplayName("local date-time keeps the documented behaviour")
    void localDateTimeStillBinds() {
        Map<String, Object> params = paramsFor("2026-09-14T16:00:00");
        Timestamp ts = params.values().stream()
                .filter(Timestamp.class::isInstance)
                .map(Timestamp.class::cast)
                .findFirst()
                .orElse(null);
        assertEquals(Timestamp.valueOf(LocalDateTime.parse("2026-09-14T16:00:00")), ts);
    }

    @Test
    @DisplayName("unparseable values keep the legacy raw-string binding")
    void unparseableFallsBackToString() {
        Map<String, Object> params = paramsFor("not-a-date");
        assertTrue(params.values().stream().anyMatch("not-a-date"::equals),
                "raw value must pass through unchanged for backwards compatibility");
    }
}
