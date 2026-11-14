package com.auraboot.framework.meta.util;

import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dense unit coverage for the pure named-query parsing layer extracted from
 * NamedQueryServiceImpl (2026-11-14 split step 1). These are the SQL-assembly
 * seams where injection and operator discipline are enforced.
 */
class NamedQueryQueryParseUtilsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static NamedQueryField field(String code, String columnExpr) {
        NamedQueryField f = new NamedQueryField();
        f.setFieldCode(code);
        f.setColumnExpr(columnExpr);
        f.setSortable(Boolean.TRUE);
        return f;
    }

    // ===== trimToNull =====

    @Test
    void trimToNull_blankAndWhitespaceBecomeNull() {
        assertThat(NamedQueryQueryParseUtils.trimToNull(null)).isNull();
        assertThat(NamedQueryQueryParseUtils.trimToNull("   ")).isNull();
        assertThat(NamedQueryQueryParseUtils.trimToNull("  x  ")).isEqualTo("x");
    }

    // ===== ALLOWED_OPERATORS discipline =====

    @Test
    void operatorWhitelist_coversDocumentedSet_andNothingElse() {
        assertThat(NamedQueryQueryParseUtils.ALLOWED_OPERATORS).containsExactlyInAnyOrder(
                "eq", "ne", "gt", "gte", "lt", "lte", "like", "ilike", "in", "not_in",
                "is_null", "is_not_null", "between", "starts_with", "ends_with", "contains");
    }

    @Test
    void parseBaseWhere_rejectsUnknownOperator() {
        List<String> clauses = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        String body = """
                [{"field":"amount","operator":"drop table","value":1}]
                """;
        assertThatThrownBy(() -> NamedQueryQueryParseUtils.parseBaseWhere(json(body), clauses, params))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("Unsupported operator");
    }

    @Test
    void parseBaseWhere_rejectsInjectionInFieldName() {
        List<String> clauses = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        String body = """
                [{"field":"a; DROP TABLE x","operator":"eq","value":1}]
                """;
        assertThatThrownBy(() -> NamedQueryQueryParseUtils.parseBaseWhere(json(body), clauses, params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid SQL identifier");
    }

    @Test
    void parseBaseWhere_buildsParameterizedClauseForValidCondition() {
        List<String> clauses = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        String body = """
                [{"field":"amount","operator":"gte","value":100}]
                """;
        NamedQueryQueryParseUtils.parseBaseWhere(json(body), clauses, params);
        assertThat(clauses).singleElement().asString()
                .isEqualTo("amount >= #{params.bw_0}");
        assertThat(params.get("bw_0")).isEqualTo(100);
    }

    // ===== buildWhereClause shapes =====

    @Test
    void buildWhereClause_comparisonOperatorsBindParams() {
        Map<String, Object> params = new HashMap<>();
        String eq = NamedQueryQueryParseUtils.buildWhereClause("t.a", "eq", "p1", json("42"), params);
        assertThat(eq).isEqualTo("t.a = #{params.p1}");
        assertThat(params.get("p1")).isEqualTo(42);

        String lte = NamedQueryQueryParseUtils.buildWhereClause("t.a", "LTE", "p2", json("7"), params);
        assertThat(lte).isEqualTo("t.a <= #{params.p2}");
    }

    @Test
    void buildWhereClause_likeOperatorsWrapWithWildcards() {
        Map<String, Object> params = new HashMap<>();
        String like = NamedQueryQueryParseUtils.buildWhereClause("t.name", "contains", "p1", json("\"abc\""), params);
        assertThat(like).contains("LIKE", "#{params.p1}");
        assertThat(String.valueOf(params.get("p1"))).contains("abc");

        String ilike = NamedQueryQueryParseUtils.buildWhereClause("t.name", "ilike", "p2", json("\"abc\""), params);
        assertThat(ilike).contains("ILIKE", "#{params.p2}");
    }

    @Test
    void buildWhereClause_nullChecksEmitSqlWithoutParams() {
        Map<String, Object> params = new HashMap<>();
        assertThat(NamedQueryQueryParseUtils.buildWhereClause("t.x", "is_null", "p1", null, params))
                .isEqualTo("t.x IS NULL");
        assertThat(NamedQueryQueryParseUtils.buildWhereClause("t.x", "is_not_null", "p2", null, params))
                .isEqualTo("t.x IS NOT NULL");
        assertThat(params).isEmpty();
    }

    @Test
    void buildWhereClause_inExpandsPlaceholderList() {
        Map<String, Object> params = new HashMap<>();
        String in = NamedQueryQueryParseUtils.buildWhereClause("t.id", "in", "p1", json("[1,2,3]"), params);
        assertThat(in).contains("t.id IN", "#{params.p1_0}", "#{params.p1_2}");
        assertThat(params.values()).contains(1, 2, 3);
    }

    @Test
    void buildWhereClause_betweenBindsOrderedPair() {
        Map<String, Object> params = new HashMap<>();
        String between = NamedQueryQueryParseUtils.buildWhereClause("t.d", "between", "p1", json("[10,20]"), params);
        assertThat(between).contains("t.d BETWEEN", "AND");
        assertThat(params.values()).containsExactlyInAnyOrder(10, 20);
    }

    // ===== quotedIdentifier =====

    @Test
    void quotedIdentifier_wrapsInDoubleQuotes() {
        assertThat(NamedQueryQueryParseUtils.quotedIdentifier("amount"))
                .isEqualTo("\"amount\"");
    }

    // ===== public output alias blacklist =====

    @Test
    void forbiddenOutputAliases_coverIdentityColumns() {
        assertThat(NamedQueryQueryParseUtils.PUBLIC_FORBIDDEN_OUTPUT_ALIASES)
                .containsExactlyInAnyOrder("id", "record_id", "tenant_id", "created_by", "updated_by");
    }

    @Test
    void validatePublicOutputAlias_rejectsIdentityColumnsCaseInsensitively() {
        assertThatThrownBy(() -> NamedQueryQueryParseUtils.validatePublicOutputAlias("TENANT_ID"))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void validatePublicOutputAlias_acceptsBusinessAliasesAndSkipsNulls() {
        assertThatCode(() -> NamedQueryQueryParseUtils.validatePublicOutputAlias(null))
                .doesNotThrowAnyException();
        assertThatCode(() -> NamedQueryQueryParseUtils.validatePublicOutputAlias("customer_name"))
                .doesNotThrowAnyException();
        assertThatCode(() -> NamedQueryQueryParseUtils.validatePublicOutputAliases(List.of()))
                .doesNotThrowAnyException();
        assertThatCode(() -> NamedQueryQueryParseUtils.validatePublicOutputAliases(null))
                .doesNotThrowAnyException();
    }

    @Test
    void validatePublicOutputAliases_rejectsWhenAnyFieldUsesReservedAlias() {
        List<NamedQueryField> fields = List.of(field("ok", "t.ok"), field("created_by", "t.created_by"));
        assertThatThrownBy(() -> NamedQueryQueryParseUtils.validatePublicOutputAliases(fields))
                .isInstanceOf(MetaServiceException.class);
    }

    // ===== extractValue type dispatch =====

    @Test
    void extractValue_dispatchesScalarJsonTypes() {
        assertThat(NamedQueryQueryParseUtils.extractValue(json("42"))).isEqualTo(42);
        assertThat(NamedQueryQueryParseUtils.extractValue(json("4.5"))).isEqualTo(4.5);
        assertThat(NamedQueryQueryParseUtils.extractValue(json("true"))).isEqualTo(Boolean.TRUE);
        assertThat(NamedQueryQueryParseUtils.extractValue(json("\"s\""))).isEqualTo("s");
        assertThat(NamedQueryQueryParseUtils.extractValue((com.fasterxml.jackson.databind.JsonNode) null)).isNull();
    }

    // ===== row hygiene =====

    @Test
    void dropEmptyRowNullEntries_filtersNullsAndPreservesMaps() {
        Map<String, Object> row = Map.of("k", "v");
        List<Map<String, Object>> out = NamedQueryQueryParseUtils.dropEmptyRowNullEntries(
                java.util.Arrays.asList(null, row, null));
        assertThat(out).containsExactly(row);
        assertThat(NamedQueryQueryParseUtils.dropEmptyRowNullEntries(null)).isEmpty();
    }

    // ===== order parsing =====

    @Test
    void mapSortColumn_mapsKnownUiColumnsAndPassesThroughUnknown() {
        assertThat(NamedQueryQueryParseUtils.mapSortColumn("createTime")).isNotBlank();
        assertThat(NamedQueryQueryParseUtils.mapSortColumn("updatedAt")).isNotBlank();
    }

    @Test
    void parseDefaultOrder_resolvesAliasThroughFieldMap() {
        Map<String, NamedQueryField> fieldMap = Map.of(
                "amount", field("amount", "t.amount_net"));
        String order = NamedQueryQueryParseUtils.parseDefaultOrder(
                json("{\"field\":\"amount\",\"direction\":\"desc\"}"), fieldMap);
        assertThat(order).isEqualTo("t.amount_net desc");
    }
}
