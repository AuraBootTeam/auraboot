package com.auraboot.framework.meta.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NamedQuerySourceScopeRewriterTest {
    private final NamedQuerySourceScopeRewriter rewriter = new NamedQuerySourceScopeRewriter();
    private final Map<String, String> scopes = Map.of(NamedQuerySourceModels.identity("orders"), "",
            NamedQuerySourceModels.identity("customers"), "created_by = 20");

    @Test void leftJoinFiltersItsInputRatherThanOuterWhere() {
        String sql = rewriter.rewrite("SELECT o.pid, c.pid FROM orders o LEFT JOIN customers c ON c.pid = o.customer WHERE o.pid = #{params.pid}", scopes);
        assertTrue(sql.contains("LEFT JOIN (SELECT * FROM customers WHERE (created_by = 20)) c ON"), sql);
        assertTrue(sql.endsWith("WHERE o.pid = #{params.pid}"), sql);
    }
    @Test void nestedAndScalarSourcesBothReceiveScope() {
        String sql = rewriter.rewrite("SELECT x.pid, (SELECT max(pid) FROM customers) AS latest FROM (SELECT pid FROM customers) x", scopes);
        assertEquals(2, sql.split("created_by = 20", -1).length - 1, sql);
    }
    @Test void repeatedTableAliasesAndUnionBranchesAreFiltered() {
        String sql = rewriter.rewrite("SELECT a.pid FROM customers a JOIN customers b ON a.pid = b.pid UNION SELECT pid FROM customers", scopes);
        assertEquals(3, sql.split("created_by = 20", -1).length - 1, sql);
        assertTrue(sql.contains(")) a JOIN"), sql);
        assertTrue(sql.contains(")) b ON"), sql);
    }
    @Test void schemaQualifiedColumnUsesPreservedDerivedTableName() {
        String sql = rewriter.rewrite("SELECT public.customers.pid FROM public.customers", scopes);
        assertTrue(sql.startsWith("SELECT customers.pid"), sql);
        assertTrue(sql.contains("FROM public.customers WHERE (created_by = 20)) AS customers"), sql);
    }
    @Test void parametersWithCommonPrefixesRemainDistinct() {
        String sql = rewriter.rewrite("SELECT pid FROM customers WHERE pid = #{params.p} OR pid = #{params.pid}", scopes);
        assertTrue(sql.contains("#{params.p}"), sql);
        assertTrue(sql.contains("#{params.pid}"), sql);
        assertFalse(sql.contains("__nq_scope_parameter"), sql);
    }
    @Test void unknownAndRecursiveSourcesCannotEscapeCoverage() {
        assertThrows(AccessDeniedException.class, () -> rewriter.rewrite("SELECT * FROM orders JOIN secret ON 1=1", scopes));
        assertThrows(AccessDeniedException.class, () -> rewriter.rewrite("WITH RECURSIVE c AS (SELECT * FROM customers) SELECT * FROM c", scopes));
    }

    @Test void chainedCtesScopeOnlyTheirPhysicalInputs() {
        String sql = rewriter.rewrite("WITH c AS (SELECT * FROM customers), d AS (SELECT * FROM c) SELECT * FROM d", scopes);
        assertEquals(1, sql.split("created_by = 20", -1).length - 1, sql);
        assertTrue(sql.contains("FROM c"), sql);
    }
    @Test void sameNamedCteAndSchemaQualifiedTableRemainDistinct() {
        String sql = rewriter.rewrite("WITH customers AS (SELECT * FROM customers) SELECT c.pid FROM customers c JOIN public.customers p ON c.pid=p.pid", scopes);
        assertEquals(2, sql.split("created_by = 20", -1).length - 1, sql);
    }
    @Test void forwardNameAndNestedShadowingStillScopePhysicalSources() {
        String sql = rewriter.rewrite("WITH c AS (SELECT * FROM customers), customers AS (SELECT * FROM orders) SELECT x.pid FROM (WITH c AS (SELECT * FROM customers) SELECT * FROM c) x JOIN c y ON x.pid=y.pid", scopes);
        assertEquals(1, sql.split("created_by = 20", -1).length - 1, sql);
        assertTrue(sql.contains("WITH c AS (SELECT * FROM customers)"), sql);
    }
    @Test void physicalSourceCollectionUsesTheSameLexicalRules() {
        var collector = new com.auraboot.framework.meta.service.SecureSqlRewriter();
        assertEquals(java.util.Set.of("customers", "orders", "public.customers"), collector.referencedTables(
                "WITH c AS (SELECT * FROM customers), customers AS (SELECT * FROM orders) SELECT c.pid FROM c JOIN public.customers p ON c.pid=p.pid"));
        assertEquals(java.util.Set.of("customers"), collector.referencedTables(
                "WITH customers AS (SELECT * FROM customers) SELECT * FROM customers"));
    }
}
