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
}
