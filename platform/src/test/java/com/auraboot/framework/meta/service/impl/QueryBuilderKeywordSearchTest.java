package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.QueryBuilderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("QueryBuilder keyword search")
class QueryBuilderKeywordSearchTest {

    private QueryBuilderService queryBuilderService;

    @BeforeEach
    void setUp() {
        queryBuilderService = new QueryBuilderServiceImpl(null);
    }

    @Test
    @DisplayName("explicit searchable numeric fields are cast to text for keyword search")
    void explicitSearchableNumericFieldsAreCastToText() {
        ModelDefinition model = ModelDefinition.builder()
            .code("test_rule")
            .tableName("test_rule")
            .fields(List.of(
                FieldDefinition.builder()
                    .code("name")
                    .columnName("name")
                    .dataType("text")
                    .searchable(true)
                    .build(),
                FieldDefinition.builder()
                    .code("priority")
                    .columnName("priority")
                    .dataType("integer")
                    .searchable(true)
                    .build()
            ))
            .build();

        QueryBuilderService.QueryBuilder query = queryBuilderService.buildConditionQuery(model, List.of());
        queryBuilderService.buildKeywordSearch(query, "678943", model);

        String sql = query.getSql().toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("name ilike"), "text searchable field should keep plain ILIKE");
        assertTrue(sql.contains("cast(priority as text) ilike"), "numeric searchable field should be cast before ILIKE");
        assertEquals(List.of("%678943%", "%678943%"), query.getParameters());
    }

    @Test
    @DisplayName("numeric fields are not part of fallback keyword search unless explicitly searchable")
    void numericFieldsAreNotFallbackSearchable() {
        ModelDefinition model = ModelDefinition.builder()
            .code("test_rule")
            .tableName("test_rule")
            .fields(List.of(
                FieldDefinition.builder()
                    .code("name")
                    .columnName("name")
                    .dataType("text")
                    .build(),
                FieldDefinition.builder()
                    .code("priority")
                    .columnName("priority")
                    .dataType("integer")
                    .build()
            ))
            .build();

        QueryBuilderService.QueryBuilder query = queryBuilderService.buildConditionQuery(model, List.of());
        queryBuilderService.buildKeywordSearch(query, "678943", model);

        String sql = query.getSql().toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("name ilike"), "fallback should still search text fields");
        assertFalse(sql.contains("cast(priority as text)"), "numeric fallback should not cast numeric fields");
        assertFalse(sql.contains("priority ilike"), "numeric fallback should not use direct ILIKE on numeric fields");
        assertEquals(List.of("%678943%"), query.getParameters());
    }
}
