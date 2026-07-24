package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.ModelDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The aggregate-binding guard: a command authorized against one master document must not
 * reach another document's derived rows.
 *
 * <p>Capability reach can prove "this command may write {@code quote_line}". It can never prove
 * "this command may write <em>Q1001's</em> {@code quote_line} rather than Q2002's" — that is what
 * this guard closes, in the SQL itself, atomically.</p>
 */
class AggregateBindingGuardTest {

    private static final String QUOTE_LINE_BINDING_COLUMN = "quote_pid";

    @AfterEach
    void clearScope() {
        MetaContext.clear();
    }

    private ModelDefinition boundModel() {
        return ModelDefinition.builder()
                .code("quote_line")
                .aggregateBinding(ModelDefinition.AggregateBinding.builder()
                        .aggregateModel("quote")
                        .localField(QUOTE_LINE_BINDING_COLUMN)
                        .build())
                .build();
    }

    private ModelDefinition unboundModel() {
        return ModelDefinition.builder().code("audit_log").build();
    }

    private StringBuilder freshSql() {
        return new StringBuilder("UPDATE mt_quote_line SET x = 1 WHERE pid = #{params.recordId}");
    }

    // ---------- the guard fires ----------

    @Test
    @DisplayName("open aggregate scope + bound model: the write is pinned to the authorized aggregate")
    void pinsWriteToAuthorizedAggregate() {
        StringBuilder sql = freshSql();
        Map<String, Object> params = new LinkedHashMap<>();

        MetaContext.runWithCommandAggregate("Q1001", () ->
                DynamicDataServiceImpl.appendAggregateBindingGuard(sql, params, boundModel()));

        assertTrue(sql.toString().contains("AND " + QUOTE_LINE_BINDING_COLUMN
                        + " = #{params.authorizedAggregateId}"),
                "guarded SQL must pin the binding column, got: " + sql);
        assertEquals("Q1001", params.get("authorizedAggregateId"));
    }

    /**
     * The load-bearing one. {@code runWithoutDataPermission} means "do not re-run the caller's
     * read projection" — a statement about re-deciding policy. This guard is not a decision, so it
     * must survive there; the inherited path is exactly where a cross-aggregate write would
     * otherwise slip through.
     */
    @Test
    @DisplayName("the guard still applies while data permission is bypassed")
    void guardSurvivesDataPermissionBypass() {
        StringBuilder sql = freshSql();
        Map<String, Object> params = new LinkedHashMap<>();

        MetaContext.runWithCommandAggregate("Q1001", () ->
                MetaContext.runWithoutDataPermission(() -> {
                    DynamicDataServiceImpl.appendAggregateBindingGuard(sql, params, boundModel());
                    return null;
                }));

        assertTrue(sql.toString().contains(QUOTE_LINE_BINDING_COLUMN),
                "zero re-decision must not mean zero constraint, got: " + sql);
        assertEquals("Q1001", params.get("authorizedAggregateId"));
    }

    // ---------- the guard stays inert ----------

    @Test
    @DisplayName("no aggregate scope: SQL is untouched")
    void inertWithoutAggregateScope() {
        StringBuilder sql = freshSql();
        String before = sql.toString();
        Map<String, Object> params = new LinkedHashMap<>();

        DynamicDataServiceImpl.appendAggregateBindingGuard(sql, params, boundModel());

        assertEquals(before, sql.toString(), "unscoped writes must behave exactly as before");
        assertFalse(params.containsKey("authorizedAggregateId"));
    }

    @Test
    @DisplayName("model declares no binding: SQL is untouched even inside an aggregate scope")
    void inertForModelsThatDidNotOptIn() {
        StringBuilder sql = freshSql();
        String before = sql.toString();
        Map<String, Object> params = new LinkedHashMap<>();

        MetaContext.runWithCommandAggregate("Q1001", () ->
                DynamicDataServiceImpl.appendAggregateBindingGuard(sql, params, unboundModel()));

        assertEquals(before, sql.toString(), "models opt in one at a time; the rest must not change");
        assertFalse(params.containsKey("authorizedAggregateId"));
    }

    @Test
    @DisplayName("null model is tolerated")
    void nullModelTolerated() {
        StringBuilder sql = freshSql();
        String before = sql.toString();

        assertDoesNotThrow(() -> MetaContext.runWithCommandAggregate("Q1001", () ->
                DynamicDataServiceImpl.appendAggregateBindingGuard(sql, new LinkedHashMap<>(), null)));
        assertEquals(before, sql.toString());
    }

    @Test
    @DisplayName("a binding column that is not a safe identifier is rejected, not interpolated")
    void rejectsUnsafeBindingColumn() {
        ModelDefinition evil = ModelDefinition.builder()
                .code("quote_line")
                .aggregateBinding(ModelDefinition.AggregateBinding.builder()
                        .localField("quote_pid = 1 OR 1=1 --")
                        .build())
                .build();

        assertThrows(RuntimeException.class, () ->
                MetaContext.runWithCommandAggregate("Q1001", () ->
                        DynamicDataServiceImpl.appendAggregateBindingGuard(
                                freshSql(), new LinkedHashMap<>(), evil)));
    }

    // ---------- scope semantics ----------

    // ---------- create-time injection ----------

    @Test
    @DisplayName("a row created under an aggregate scope is stamped with the authorized aggregate")
    void createStampsAuthorizedAggregate() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("amount", 10);

        MetaContext.runWithCommandAggregate("Q1001", () -> {
            DynamicDataServiceImpl.injectAggregateBinding(boundModel(), row);
            return null;
        });

        assertEquals("Q1001", row.get(QUOTE_LINE_BINDING_COLUMN));
    }

    /**
     * The client does not get to choose which document its row lands under. Without this, a caller
     * could plant rows beneath another aggregate and reach them later through a legitimate scope.
     */
    @Test
    @DisplayName("a payload cannot choose its own aggregate: the authorized one overwrites it")
    void createOverwritesClaimedAggregate() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(QUOTE_LINE_BINDING_COLUMN, "Q2002");

        MetaContext.runWithCommandAggregate("Q1001", () -> {
            DynamicDataServiceImpl.injectAggregateBinding(boundModel(), row);
            return null;
        });

        assertEquals("Q1001", row.get(QUOTE_LINE_BINDING_COLUMN),
                "the aggregate the entry authorized must win over the one the payload claimed");
    }

    @Test
    @DisplayName("create injection is inert without a scope or a binding")
    void createInjectionInertWhenNotApplicable() {
        Map<String, Object> noScope = new LinkedHashMap<>();
        DynamicDataServiceImpl.injectAggregateBinding(boundModel(), noScope);
        assertFalse(noScope.containsKey(QUOTE_LINE_BINDING_COLUMN));

        Map<String, Object> unbound = new LinkedHashMap<>();
        MetaContext.runWithCommandAggregate("Q1001", () -> {
            DynamicDataServiceImpl.injectAggregateBinding(unboundModel(), unbound);
            return null;
        });
        assertTrue(unbound.isEmpty());
    }

    @Test
    @DisplayName("a binding field code resolves to its physical column in SQL")
    void bindingResolvesFieldCodeToColumn() {
        ModelDefinition model = ModelDefinition.builder()
                .code("quote_line")
                .fields(java.util.List.of(com.auraboot.framework.meta.dto.FieldDefinition.builder()
                        .code("quotePid").columnName("quote_pid").build()))
                .aggregateBinding(ModelDefinition.AggregateBinding.builder()
                        .localField("quotePid").build())
                .build();
        StringBuilder sql = freshSql();

        MetaContext.runWithCommandAggregate("Q1001", () ->
                DynamicDataServiceImpl.appendAggregateBindingGuard(sql, new LinkedHashMap<>(), model));

        assertTrue(sql.toString().contains("AND quote_pid = "),
                "SQL must use the physical column, not the field code, got: " + sql);
    }

    // ---------- scope semantics ----------

    @Test
    @DisplayName("an aggregate scope must name the aggregate it pins to")
    void blankAggregateRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> MetaContext.runWithCommandAggregate("  ", () -> null));
        assertThrows(IllegalArgumentException.class,
                () -> MetaContext.runWithCommandAggregate(null, () -> null));
    }

    @Test
    @DisplayName("a nested scope restores the outer aggregate rather than clearing it")
    void nestedScopeRestoresOuter() {
        MetaContext.runWithCommandAggregate("Q1001", () -> {
            MetaContext.runWithCommandAggregate("Q2002", () -> {
                assertEquals("Q2002", MetaContext.getCommandAggregateId());
                return null;
            });
            assertEquals("Q1001", MetaContext.getCommandAggregateId(),
                    "leaving a nested command must not strip the outer aggregate");
            return null;
        });
        assertEquals(null, MetaContext.getCommandAggregateId(), "scope must not leak past its block");
    }
}
