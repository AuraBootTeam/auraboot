package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.exception.MetaServiceException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class NamedQueryColumnLineageTest {
    private final NamedQueryColumnLineage resolver = new NamedQueryColumnLineage();
    private NamedQueryField field(String expression) {
        NamedQueryField field = new NamedQueryField();
        field.setFieldCode("display_title");
        field.setColumnExpr(expression);
        return field;
    }
    @Test void directAliasResolvesPhysicalColumn() {
        var origin = resolver.resolve("mt_orders o", List.of(field("o.title"))).get("display_title");
        assertEquals(Set.of(new NamedQueryColumnLineage.PhysicalColumn("mt_orders", "title")), origin.columns());
        assertTrue(origin.direct());
    }
    @Test void nestedRenamesPreservePhysicalSourceAndParameters() {
        var origin = resolver.resolve("SELECT x.renamed AS public_title FROM (SELECT title AS renamed FROM mt_orders WHERE pid = #{params.pid}) x",
                List.of(field("public_title"))).get("display_title");
        assertEquals(Set.of(new NamedQueryColumnLineage.PhysicalColumn("mt_orders", "title")), origin.columns());
        assertTrue(origin.direct());
    }
    @Test void transformedInnerProjectionNeverBecomesDirectThroughAlias() {
        var origin = resolver.resolve("SELECT upper(title) AS renamed FROM mt_orders", List.of(field("renamed"))).get("display_title");
        assertFalse(origin.direct());
        assertTrue(origin.opaqueFunction());
        assertEquals(Set.of(new NamedQueryColumnLineage.PhysicalColumn("mt_orders", "title")), origin.columns());
    }
    @Test void qualifiedJoinRetainsTableIdentity() {
        var origin = resolver.resolve("mt_orders o JOIN mt_users u ON o.owner = u.pid", List.of(field("u.name"))).get("display_title");
        assertEquals(Set.of(new NamedQueryColumnLineage.PhysicalColumn("mt_users", "name")), origin.columns());
    }
    @Test void ambiguousJoinFailsInsteadOfGuessing() {
        assertThrows(MetaServiceException.class, () -> resolver.resolve("mt_orders o JOIN mt_users u ON o.owner = u.pid", List.of(field("name"))));
    }
    @Test void wildcardProjectionPreservesSource() {
        var origin = resolver.resolve("SELECT * FROM mt_orders", List.of(field("title"))).get("display_title");
        assertEquals(Set.of(new NamedQueryColumnLineage.PhysicalColumn("mt_orders", "title")), origin.columns());
    }
    @Test void setOperationsAndScalarSubqueriesRequireResolution() {
        assertThrows(MetaServiceException.class, () -> resolver.resolve("SELECT title FROM mt_orders UNION SELECT name FROM mt_users", List.of(field("title"))));
        assertThrows(MetaServiceException.class, () -> resolver.resolve("mt_orders", List.of(field("(SELECT name FROM mt_users LIMIT 1)"))));
        assertThrows(MetaServiceException.class, () -> resolver.resolve("mt_orders", List.of(field("coalesce((SELECT name FROM mt_users LIMIT 1), title)"))));
    }

    @Test void chainedCteColumnAliasesPreserveProtectedOrigin() {
        var origin = resolver.resolve("WITH a(renamed) AS (SELECT title FROM mt_orders), b AS (SELECT renamed AS label FROM a) SELECT label FROM b",
                List.of(field("label"))).get("display_title");
        assertEquals(Set.of(new NamedQueryColumnLineage.PhysicalColumn("mt_orders", "title")), origin.columns());
        assertTrue(origin.direct());
    }
    @Test void cteShadowingAndForwardPhysicalNamesResolveLexically() {
        var origin = resolver.resolve("WITH a AS (SELECT title FROM mt_orders), mt_orders AS (SELECT name AS title FROM mt_users) SELECT label FROM (WITH a AS (SELECT title AS label FROM mt_orders) SELECT label FROM a) x",
                List.of(field("label"))).get("display_title");
        assertEquals(Set.of(new NamedQueryColumnLineage.PhysicalColumn("mt_users", "name")), origin.columns());
        var physical = resolver.resolve("WITH mt_orders AS (SELECT title FROM mt_orders) SELECT title FROM mt_orders",
                List.of(field("title"))).get("display_title");
        assertEquals(Set.of(new NamedQueryColumnLineage.PhysicalColumn("mt_orders", "title")), physical.columns());
    }
    @Test void recursiveAndWildcardColumnListsAreExplicitlyRejected() {
        assertThrows(MetaServiceException.class, () -> resolver.resolve("WITH RECURSIVE c AS (SELECT title FROM mt_orders) SELECT title FROM c", List.of(field("title"))));
        assertThrows(MetaServiceException.class, () -> resolver.resolve("WITH c(label) AS (SELECT * FROM mt_orders) SELECT label FROM c", List.of(field("label"))));
    }

    @Test void viewAliasRetainsBothViewAndUnderlyingFieldProtection() {
        var views = java.util.Map.of(NamedQuerySourceModels.identity("order_view"), "SELECT title AS label, tenant_id FROM mt_orders");
        var origin = new NamedQueryColumnLineage(views).resolve("order_view", List.of(field("label"))).get("display_title");
        assertEquals(Set.of(new NamedQueryColumnLineage.PhysicalColumn("order_view", "label"),
                new NamedQueryColumnLineage.PhysicalColumn("mt_orders", "title")), origin.columns());
        assertTrue(origin.direct());
    }
}
