package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.SecureSqlRewriter;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ToolSqlTenantScopeTest {
    private final SecureSqlRewriter sql = new SecureSqlRewriter();

    @Test void scopesPhysicalInputBeforeOrPredicates() {
        String result = sql.rewriteForTenant("SELECT code FROM ab_meta_model WHERE tenant_id = 88 OR 1=1 ORDER BY code");
        assertThat(result).contains("(SELECT * FROM", "WHERE (tenant_id = #{params.tenantId})", "AS ab_meta_model", "tenant_id = 88 OR 1 = 1", "ORDER BY code");
    }

    @Test void scopesEachJoinAndNestedSelect() {
        String result = sql.rewriteForTenant("SELECT a.code, (SELECT max(b.code) FROM ab_meta_model b) AS latest FROM ab_meta_model a LEFT JOIN ab_meta_model c ON a.pid=c.pid WHERE NOT (a.code='x' AND c.code='y')");
        assertThat(result.split("tenant_id = #\\{params.tenantId}", -1)).hasSize(4);
        assertThat(result).contains("})) a", "})) b", "})) c", "LEFT JOIN", "NOT (a.code = 'x' AND c.code = 'y')");
    }

    @Test void handlesQuotedSchemaAndLiteralKeywords() {
        String result = sql.rewriteForTenant("SELECT public.ab_meta_model.code, 'where limit from' AS note FROM public.ab_meta_model WHERE code='group by'");
        assertThat(result).contains("ab_meta_model.code", "'where limit from'", "'group by'", "WHERE (tenant_id = #{params.tenantId})");
    }

    @Test void invalidStatementCannotFallBackToRawSql() {
        assertThatThrownBy(() -> sql.rewriteForTenant("SELECT FROM (")).isInstanceOf(RuntimeException.class);
    }

    @Test void constantsRemainUsableWithoutATable() {
        assertThat(sql.rewriteForTenant("SELECT 1 AS value")).isEqualTo("SELECT 1 AS value");
    }
}
