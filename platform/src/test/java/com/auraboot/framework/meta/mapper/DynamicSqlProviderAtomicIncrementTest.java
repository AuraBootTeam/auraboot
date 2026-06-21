package com.auraboot.framework.meta.mapper;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicSqlProviderAtomicIncrementTest {

    private Map<String, Object> baseParams() {
        Map<String, Object> p = new HashMap<>();
        p.put("tableName", "mt_cr_crawl_job");
        p.put("counterCol", "cr_cj_discovered_count");
        p.put("pkColumn", "pid");
        p.put("softDeleteClause", " AND (deleted_flag = FALSE OR deleted_flag IS NULL)");
        return p;
    }

    @Test
    void capped_sql_has_coalesce_cap_predicate_and_returning() {
        Map<String, Object> p = baseParams();
        p.put("capCol", "cr_cj_max_urls");

        String sql = DynamicSqlProvider.atomicIncrementReturning(p);

        assertThat(sql).startsWith("UPDATE mt_cr_crawl_job SET");
        assertThat(sql).contains("cr_cj_discovered_count = COALESCE(cr_cj_discovered_count, 0) + #{delta}");
        assertThat(sql).contains("updated_at = now()");
        assertThat(sql).contains("updated_by = #{currentUserId}");
        assertThat(sql).contains("WHERE pid = #{recordId}");
        assertThat(sql).contains("AND tenant_id = #{tenantId}");
        assertThat(sql).contains("AND COALESCE(cr_cj_discovered_count, 0) + #{delta} <= cr_cj_max_urls");
        assertThat(sql).contains("AND (deleted_flag = FALSE OR deleted_flag IS NULL)");
        assertThat(sql).endsWith("RETURNING cr_cj_discovered_count AS new_value");
    }

    @Test
    void uncapped_sql_omits_cap_predicate() {
        Map<String, Object> p = baseParams(); // no capCol
        String sql = DynamicSqlProvider.atomicIncrementReturning(p);
        assertThat(sql).doesNotContain("<=");
        assertThat(sql).endsWith("RETURNING cr_cj_discovered_count AS new_value");
    }

    @Test
    void rejects_non_identifier_counter_column() {
        Map<String, Object> p = baseParams();
        p.put("counterCol", "discovered; DROP TABLE x");
        assertThatThrownBy(() -> DynamicSqlProvider.atomicIncrementReturning(p))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
