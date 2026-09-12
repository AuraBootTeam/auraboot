package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.NamedQueryField;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.SecureSqlRewriter;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NamedQuerySourceModelsTest {
    private final MetaModelMapper mapper = mock(MetaModelMapper.class);
    private final SecureSqlRewriter sql = mock(SecureSqlRewriter.class);
    private final NamedQuerySourceModels resolver = new NamedQuerySourceModels(mapper, sql);
    private Model model(String code, String table) {
        Model model = new Model(); model.setCode(code); model.setTableName(table); return model;
    }
    private Map<String, String> resolve(String table) {
        when(sql.referencedTables(anyString())).thenReturn(Set.of(table));
        NamedQueryField field = new NamedQueryField(); field.setFieldCode("title"); field.setColumnExpr("title");
        return resolver.resolve(42L, table, List.of(field));
    }
    @Test void generatedAndCustomTablesUseExplicitTenant() {
        when(mapper.findCurrentForTenant(42L)).thenReturn(List.of(model("orders", null), model("users", "custom_users")));
        assertEquals(Map.of("\"public\".\"mt_orders\"", "orders"), resolve("public.mt_orders"));
        assertEquals(Map.of("\"public\".\"custom_users\"", "users"), resolve("custom_users"));
        verify(mapper, times(2)).findCurrentForTenant(42L);
    }
    @Test void unknownAndDifferentSchemaAreDenied() {
        when(mapper.findCurrentForTenant(42L)).thenReturn(List.of(model("orders", null)));
        assertThrows(AccessDeniedException.class, () -> resolve("missing"));
        assertThrows(AccessDeniedException.class, () -> resolve("private.mt_orders"));
    }
    @Test void ambiguousPhysicalMappingIsDenied() {
        when(mapper.findCurrentForTenant(42L)).thenReturn(List.of(model("orders", null), model("shadow", "mt_orders")));
        assertThrows(AccessDeniedException.class, () -> resolve("mt_orders"));
    }
    @Test void quotedIdentifiersRetainCaseAndEmbeddedDots() {
        assertEquals(NamedQuerySourceModels.identity("mt_orders"), NamedQuerySourceModels.identity("public.\"mt_orders\""));
        assertNotEquals(NamedQuerySourceModels.identity("mt_orders"), NamedQuerySourceModels.identity("\"MT_ORDERS\""));
        assertNotEquals(NamedQuerySourceModels.identity("\"private.mt_orders\""), NamedQuerySourceModels.identity("private.mt_orders"));
        assertThrows(AccessDeniedException.class, () -> NamedQuerySourceModels.identity("db.public.mt_orders"));
    }
}
