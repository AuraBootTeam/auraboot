package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.service.SecureSqlRewriter;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Executes the production AST source scoper against real PostgreSQL tenant fixtures. */
class ToolSqlTenantScopeIT {
    @Test void everyPhysicalSourceIsScopedBeforeCallerPredicates() throws Exception {
        String url = System.getenv("AURA_TEST_JDBC_URL");
        assertThat(url).as("Explicit owned runtime JDBC target").isNotBlank();
        try (Connection connection = DriverManager.getConnection(url)) {
            assertThat(connection.getCatalog()).isEqualTo(System.getenv("POSTGRES_DB"));
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TEMP TABLE tool_scope_fixture (tenant_id bigint, code text)");
                statement.execute("INSERT INTO tool_scope_fixture VALUES (11,'own'),(22,'foreign')");
            }
            var rewriter = new SecureSqlRewriter();
            for (String query : List.of(
                "SELECT code FROM tool_scope_fixture WHERE tenant_id=22 OR 1=1",
                "SELECT code FROM tool_scope_fixture WHERE NOT (tenant_id=22 AND code='absent')",
                "SELECT code FROM tool_scope_fixture WHERE code IN (SELECT code FROM tool_scope_fixture WHERE 1=1 OR tenant_id=22)",
                "SELECT a.code FROM tool_scope_fixture a LEFT JOIN tool_scope_fixture b ON 1=1 WHERE a.tenant_id=22 OR 1=1",
                "SELECT (SELECT max(code) FROM tool_scope_fixture WHERE 1=1 OR tenant_id=22) AS code FROM tool_scope_fixture LIMIT 500")) {
                // Production scopes normalize unqualified names to public. Use a dedicated
                // schema-qualified temp relation so this fixture follows the same path.
                String physical = query.replace("tool_scope_fixture", "pg_temp.tool_scope_fixture");
                String scoped = rewriter.rewriteForTenant(physical).replace("#{params.tenantId}", "?");
                try (PreparedStatement statement = connection.prepareStatement(scoped)) {
                    int count = statement.getParameterMetaData().getParameterCount();
                    for (int index=1; index<=count; index++) statement.setLong(index, 11L);
                    List<String> actual = new ArrayList<>();
                    try (ResultSet rows = statement.executeQuery()) { while (rows.next()) actual.add(rows.getString(1)); }
                    assertThat(actual).as(query).containsExactly("own");
                }
            }
            // Observe the vulnerable predicate on the same nonempty database: this is a real red control.
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                    "SELECT code FROM tool_scope_fixture WHERE tenant_id=11 AND tenant_id=22 OR 1=1 ORDER BY code")) {
                List<String> leaked = new ArrayList<>(); while (rows.next()) leaked.add(rows.getString(1));
                assertThat(leaked).containsExactly("foreign", "own");
            }
        }
    }
}
