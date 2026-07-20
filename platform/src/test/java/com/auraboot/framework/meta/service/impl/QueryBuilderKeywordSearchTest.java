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
    @DisplayName("text-declared field whose PHYSICAL column is JSONB is CAST to text (robust defense)")
    void textDeclaredButPhysicallyJsonbIsCast() {
        // Defense-in-depth: a global field code can be jsonb in one table and text in
        // another; ab_meta_field stores one dataType per code, so the declaration can be
        // wrong for a given model. When live introspection reports the column is JSONB,
        // keyword search must CAST it instead of emitting a bare (jsonb) ILIKE.
        ModelDefinition model = ModelDefinition.builder()
            .code("test_conn")
            .tableName("test_conn")
            .fields(List.of(
                FieldDefinition.builder().code("name").columnName("name").dataType("text").searchable(true).build(),
                FieldDefinition.builder().code("auth_config").columnName("auth_config").dataType("text").searchable(true).build()
            ))
            .build();

        QueryBuilderServiceImpl impl = new QueryBuilderServiceImpl(null);
        impl.setTableMetadataService(new com.auraboot.framework.meta.ddl.TableMetadataService(null, null) {
            @Override
            public String getColumnTypeDefinition(String tableName, String columnName) {
                return "auth_config".equals(columnName) ? "jsonb" : "VARCHAR(200)";
            }
        });

        QueryBuilderService.QueryBuilder query = impl.buildConditionQuery(model, List.of());
        impl.buildKeywordSearch(query, "secret", model);

        String sql = query.getSql().toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("name ilike"), "plain text column keeps bare ILIKE");
        assertTrue(sql.contains("cast(auth_config as text) ilike"), "physically-jsonb column must be CAST");
        assertFalse(sql.contains("(auth_config ilike"), "no bare ILIKE on a jsonb column");
    }

    @Test
    @DisplayName("json/jsonb fields are excluded from fallback keyword search (no bare ILIKE on jsonb columns)")
    void jsonFieldsAreExcludedFromKeywordSearch() {
        // Regression for the api-connector list-search 500: a JSONB column declared
        // as a JSON field must NOT be keyword-searched with a bare ILIKE, which
        // Postgres rejects with `operator does not exist: jsonb ~~* character varying`.
        ModelDefinition model = ModelDefinition.builder()
            .code("test_connector")
            .tableName("test_connector")
            .fields(List.of(
                FieldDefinition.builder()
                    .code("name")
                    .columnName("name")
                    .dataType("string")
                    .build(),
                FieldDefinition.builder()
                    .code("auth_config")
                    .columnName("auth_config")
                    .dataType("json")
                    .build()
            ))
            .build();

        QueryBuilderService.QueryBuilder query = queryBuilderService.buildConditionQuery(model, List.of());
        queryBuilderService.buildKeywordSearch(query, "secret", model);

        String sql = query.getSql().toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("name ilike"), "fallback should still search the text field");
        assertFalse(sql.contains("auth_config ilike"), "jsonb column must not get a bare ILIKE");
        assertFalse(sql.contains("auth_config as text"), "jsonb config column should be excluded entirely, not cast-searched");
        assertEquals(List.of("%secret%"), query.getParameters());
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

    @Test
    @DisplayName("F12: a field DECLARED text but physically BIGINT is cast, not ILIKE'd raw")
    void keywordSearchCastsDeclaredTextButPhysicallyNumericColumn() {
        // ab_mission.owner_id is declared "string" in model metadata while the
        // physical column is BIGINT. A bare ILIKE produced
        //   ERROR: operator does not exist: bigint ~~* character varying
        // and every keyword search on the model returned HTTP 500 (list page search
        // box, agent list tool, everything). The declaration is not proof of the
        // physical type — decide by live introspection.
        ModelDefinition model = ModelDefinition.builder()
            .code("mission")
            .tableName("ab_mission")
            .fields(List.of(
                FieldDefinition.builder().code("title").columnName("title").dataType("string").searchable(true).build(),
                FieldDefinition.builder().code("owner_id").columnName("owner_id").dataType("string").searchable(true).build()
            ))
            .build();

        QueryBuilderServiceImpl impl = new QueryBuilderServiceImpl(null);
        impl.setTableMetadataService(new com.auraboot.framework.meta.ddl.TableMetadataService(null, null) {
            @Override
            public String getColumnTypeDefinition(String tableName, String columnName) {
                return "owner_id".equals(columnName) ? "bigint" : "VARCHAR(200)";
            }
        });

        QueryBuilderService.QueryBuilder query = impl.buildConditionQuery(model, List.of());
        impl.buildKeywordSearch(query, "acme", model);

        String sql = query.getSql().toLowerCase(Locale.ROOT);
        assertTrue(sql.contains("cast(owner_id as text) ilike"), sql);
        // The genuinely-text column keeps its bare, index-friendly form.
        assertTrue(sql.contains("title ilike"), sql);
        assertFalse(sql.contains("cast(title as text)"), sql);
    }
}
