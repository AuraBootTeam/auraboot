package com.auraboot.framework.meta.mapper;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for {@link DynamicSqlProvider#batchInsert(Map)}.
 *
 * <p>Root cause historically: the column list and per-row value bindings were both
 * derived from {@code dataList.get(0).keySet()}. Any key that only appears in a
 * later row (e.g. BOM carry-forward's {@code confirmed_by}/{@code confirmed_at})
 * was silently dropped from the entire batch. The fix uses the first-seen-order
 * union of every row's keys for both the column list and every row's value tuple.
 */
class DynamicSqlProviderBatchInsertTest {

    private static Map<String, Object> row(String... keys) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : keys) {
            m.put(k, "v_" + k);
        }
        return m;
    }

    @SafeVarargs
    private static Map<String, Object> params(String table, Map<String, Object>... rows) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tableName", table);
        List<Map<String, Object>> list = new ArrayList<>();
        Collections.addAll(list, rows);
        p.put("dataList", list);
        return p;
    }

    /**
     * Core red→green: first row is missing {@code c}, a later row has it.
     * Pre-fix the column list is only {@code (a, b)} and this fails.
     */
    @Test
    void first_row_missing_key_is_still_included_from_union() {
        String sql = DynamicSqlProvider.batchInsert(params("mt_bom_line", row("a", "b"), row("a", "b", "c")));

        assertThat(sql).isEqualTo(
                "INSERT INTO mt_bom_line (a, b, c) VALUES "
                        + "(#{dataList[0].a}, #{dataList[0].b}, #{dataList[0].c}), "
                        + "(#{dataList[1].a}, #{dataList[1].b}, #{dataList[1].c})");
    }

    /**
     * A key present only in the first row must still bind for later rows that lack
     * it. OGNL resolves the missing Map key to null, so the placeholder still
     * appears in the SQL and column order == value order.
     */
    @Test
    void later_row_missing_first_row_key_still_binds_placeholder() {
        String sql = DynamicSqlProvider.batchInsert(params("mt_bom_line", row("a", "b", "c"), row("a", "b")));

        assertThat(sql).isEqualTo(
                "INSERT INTO mt_bom_line (a, b, c) VALUES "
                        + "(#{dataList[0].a}, #{dataList[0].b}, #{dataList[0].c}), "
                        + "(#{dataList[1].a}, #{dataList[1].b}, #{dataList[1].c})");
    }

    /**
     * BOM carry-forward shape: parsed rows have no confirmation columns, carry rows
     * add {@code confirmed_by}/{@code confirmed_at}. The union keeps them.
     */
    @Test
    void bom_carry_forward_columns_are_not_dropped() {
        String sql = DynamicSqlProvider.batchInsert(params(
                "mt_bom_line",
                row("bom_code", "material_code"),
                row("bom_code", "material_code", "confirmed_by", "confirmed_at")));

        assertThat(sql).contains("(bom_code, material_code, confirmed_by, confirmed_at)");
        assertThat(sql).contains("#{dataList[1].confirmed_by}");
        assertThat(sql).contains("#{dataList[1].confirmed_at}");
        // First (short) row also binds the extra columns (OGNL null).
        assertThat(sql).contains("#{dataList[0].confirmed_by}");
        assertThat(sql).contains("#{dataList[0].confirmed_at}");
    }

    /**
     * Backward compatibility: when all rows have identical shape the union equals
     * the first row's keySet, so output is byte-for-byte the historical form.
     */
    @Test
    void uniform_rows_produce_byte_identical_sql() {
        String sql = DynamicSqlProvider.batchInsert(params("mt_bom_line", row("a", "b"), row("a", "b")));

        assertThat(sql).isEqualTo(
                "INSERT INTO mt_bom_line (a, b) VALUES "
                        + "(#{dataList[0].a}, #{dataList[0].b}), "
                        + "(#{dataList[1].a}, #{dataList[1].b})");
    }

    @Test
    void single_row_batch_insert() {
        String sql = DynamicSqlProvider.batchInsert(params("mt_bom_line", row("a", "b")));

        assertThat(sql).isEqualTo(
                "INSERT INTO mt_bom_line (a, b) VALUES (#{dataList[0].a}, #{dataList[0].b})");
    }

    /**
     * Injection guard runs over the full union, not just the first row: an illegal
     * column name introduced by a later row must still be rejected.
     */
    @Test
    void rejects_illegal_column_name_introduced_by_later_row() {
        assertThatThrownBy(() -> DynamicSqlProvider.batchInsert(
                params("mt_bom_line", row("a", "b"), row("a", "b", "c); DROP TABLE x"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_illegal_column_name_in_first_row() {
        assertThatThrownBy(() -> DynamicSqlProvider.batchInsert(
                params("mt_bom_line", row("a; DROP TABLE x", "b"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void empty_data_list_throws() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tableName", "mt_bom_line");
        p.put("dataList", new ArrayList<Map<String, Object>>());
        assertThatThrownBy(() -> DynamicSqlProvider.batchInsert(p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Batch insert data cannot be empty");
    }

    @Test
    void null_data_list_throws() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tableName", "mt_bom_line");
        assertThatThrownBy(() -> DynamicSqlProvider.batchInsert(p))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void empty_first_row_throws() {
        assertThatThrownBy(() -> DynamicSqlProvider.batchInsert(
                params("mt_bom_line", new LinkedHashMap<>())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Batch insert row cannot be empty");
    }
}
