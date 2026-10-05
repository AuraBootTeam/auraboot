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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private final com.auraboot.framework.plugin.pf4j.AuraPluginManager plugins = mock(com.auraboot.framework.plugin.pf4j.AuraPluginManager.class);
    private final NamedQuerySourceModels resolver = new NamedQuerySourceModels(mapper, sql, jdbc, plugins);
    @org.junit.jupiter.api.BeforeEach void tenantMetadata() {
        when(jdbc.queryForMap(anyString(), anyString())).thenReturn(Map.of("kind", "r", "tenant_column", true, "definition", ""));
    }
    private Model model(String code, String table) {
        Model model = new Model(); model.setCode(code); model.setTableName(table); return model;
    }
    private Map<String, String> resolve(String table) {
        when(sql.referencedTables(anyString())).thenReturn(Set.of(table));
        NamedQueryField field = new NamedQueryField(); field.setFieldCode("title"); field.setColumnExpr("title");
        return resolver.resolve(42L, table, List.of(field));
    }
    private com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog bindSources(List<com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog.BoundModelSource> sources) {
        var catalog = mock(com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog.class);
        org.springframework.test.util.ReflectionTestUtils.setField(resolver, "runtimeCatalog", catalog);
        org.springframework.test.util.ReflectionTestUtils.setField(resolver, "defaultApplicationCode", "aura-edu");
        org.springframework.test.util.ReflectionTestUtils.setField(resolver, "runtimePrimaryEnabled", true);
        when(catalog.modelSources(42L, "aura-edu")).thenReturn(Optional.of(sources));
        return catalog;
    }

    @Test void exactBoundReleaseResolvesSourcesWithoutLegacyMetadata() {
        var catalog = bindSources(List.of(new com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog.BoundModelSource("orders", "mt_orders")));
        assertEquals(Map.of(identity("mt_orders"), "orders"), resolve("mt_orders"));
        verify(catalog).modelSources(42L, "aura-edu");
        verify(mapper, never()).findCurrentForTenant(anyLong());
    }

    @Test void boundReleaseDoesNotAdmitStaleOrAmbiguousSources() {
        bindSources(List.of());
        when(mapper.findCurrentForTenant(42L)).thenReturn(List.of(model("orders", null)));
        assertThrows(AccessDeniedException.class, () -> resolve("mt_orders"));
        bindSources(List.of(
                new com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog.BoundModelSource("orders", "mt_orders"),
                new com.auraboot.framework.application.release.ApplicationRuntimeDefinitionCatalog.BoundModelSource("shadow", "mt_orders")));
        assertThrows(AccessDeniedException.class, () -> resolve("mt_orders"));
        verify(mapper, never()).findCurrentForTenant(anyLong());
    }

    @Test void unboundReleaseRetainsTenantMetadataResolution() {
        var catalog = bindSources(List.of());
        when(catalog.modelSources(42L, "aura-edu")).thenReturn(Optional.empty());
        when(mapper.findCurrentForTenant(42L)).thenReturn(List.of(model("orders", null)));
        assertEquals(Map.of(identity("mt_orders"), "orders"), resolve("mt_orders"));
        verify(mapper).findCurrentForTenant(42L);
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
    @Test void sourcesWithoutTenantColumnsAreDenied() {
        when(mapper.findCurrentForTenant(42L)).thenReturn(List.of(model("orders", null)));
        when(jdbc.queryForMap(anyString(), anyString())).thenReturn(Map.of("kind", "r", "tenant_column", false, "definition", ""));
        assertThrows(AccessDeniedException.class, () -> resolve("mt_orders"));
    }
    @Test void quotedIdentifiersRetainCaseAndEmbeddedDots() {
        assertEquals(NamedQuerySourceModels.identity("mt_orders"), NamedQuerySourceModels.identity("public.\"mt_orders\""));
        assertNotEquals(NamedQuerySourceModels.identity("mt_orders"), NamedQuerySourceModels.identity("\"MT_ORDERS\""));
        assertNotEquals(NamedQuerySourceModels.identity("\"private.mt_orders\""), NamedQuerySourceModels.identity("private.mt_orders"));
        assertThrows(AccessDeniedException.class, () -> NamedQuerySourceModels.identity("db.public.mt_orders"));
    }

    @Test void viewSourceRefIncludesItsUnderlyingModelAndDefinition() {
        Model view = model("order_view", null); view.setSourceType("sqlView"); view.setSourceRef("v_orders");
        when(mapper.findCurrentForTenant(42L)).thenReturn(List.of(model("orders", null), view));
        when(jdbc.queryForMap(anyString(), eq(identity("v_orders"))))
                .thenReturn(Map.of("kind", "v", "tenant_column", true, "definition", "SELECT title, tenant_id FROM mt_orders;"));
        when(sql.referencedTables(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).contains("FROM v_orders") ? Set.of("v_orders") : Set.of("mt_orders"));
        NamedQueryField field = new NamedQueryField(); field.setFieldCode("title"); field.setColumnExpr("title");
        var plan = resolver.resolvePlan(42L, "v_orders", List.of(field));
        assertEquals(Map.of(identity("v_orders"), "order_view", identity("mt_orders"), "orders"), plan.models());
        assertEquals(Map.of(identity("v_orders"), "SELECT title, tenant_id FROM mt_orders"), plan.views());
        when(sql.referencedTables("SELECT title, tenant_id FROM mt_orders")).thenReturn(Set.of("secret"));
        assertThrows(AccessDeniedException.class, () -> resolver.resolvePlan(42L, "v_orders", List.of(field)));
    }
    @Test void materializedAndForeignRelationsNeedSeparateSemantics() {
        when(mapper.findCurrentForTenant(42L)).thenReturn(List.of(model("orders", null)));
        for (String kind : List.of("m", "f")) {
            when(jdbc.queryForMap(anyString(), anyString())).thenReturn(Map.of("kind", kind, "tenant_column", true, "definition", ""));
            assertThrows(AccessDeniedException.class, () -> resolve("mt_orders"));
        }
    }
    private static String identity(String table) { return NamedQuerySourceModels.identity(table); }


    @Test void engineBypassTableWithoutModelResolvesAsSystemSource() {
        // E10: engine tables (se_*) under the tenant-bypass prefixes have no meta
        // model but carry their own tenant_id column — resolve as SYSTEM sources
        // instead of failing approval-history style named queries.
        when(mapper.findCurrentForTenant(42L)).thenReturn(List.of());
        when(jdbc.queryForMap(anyString(), anyString())).thenReturn(
                Map.of("kind", "r", "tenant_column", true, "definition", ""));
        Map<String, String> resolved = resolve("public.se_task_instance");
        assertTrue(resolved.containsKey("\"public\".\"se_task_instance\""));
        assertTrue(resolved.get("\"public\".\"se_task_instance\"")
                .startsWith(NamedQuerySourceModels.ENGINE_SOURCE_MARKER_PREFIX));
    }

    @Test void nonBypassUnknownTableIsStillDenied() {
        when(mapper.findCurrentForTenant(42L)).thenReturn(List.of());
        assertThrows(AccessDeniedException.class, () -> resolve("public.some_business_table"));
    }
    @Test void applicationConfiguredBypassPrefixIsResolvedWithoutProductKnowledge() {
        org.springframework.test.util.ReflectionTestUtils.setField(
                resolver, "tenantBypassTablePrefixes", "se_,app_engine_");
        when(mapper.findCurrentForTenant(42L)).thenReturn(List.of());
        Map<String, String> resolved = resolve("public.app_engine_job");
        assertEquals("engine.app_engine_job", resolved.get("\"public\".\"app_engine_job\""));
    }
        @Test void platformSharedUserTableResolvesAsSystemSource() {
        when(mapper.findCurrentForTenant(42L)).thenReturn(List.of(model("users", "ab_user")));
        Map<String, String> resolved = resolve("public.ab_user");
        assertTrue(resolved.containsKey("\"public\".\"ab_user\""));
    }
    @Test void platformTenantRegistryResolvesWithoutAnyModel() {
        // ab_tenant is the platform tenant registry: no tenant_id column, no meta model on
        // any database (fresh-seed included). It must resolve as a platform reference source
        // regardless of catalog state, or every NQ touching it is denied on clean databases
        // (the AggregateQueryServiceIntegrationTest fresh-seed red cluster).
        when(mapper.findCurrentForTenant(42L)).thenReturn(List.of());
        Map<String, String> resolved = resolve("public.ab_tenant");
        assertTrue(resolved.containsKey("\"public\".\"ab_tenant\""));
        assertEquals(NamedQuerySourceModels.PLATFORM_REFERENCE_MARKER,
                resolved.get("\"public\".\"ab_tenant\""));
    }
    @Test void platformMemberDirectoryResolvesWithoutAnyModel() {
        // ab_tenant_member is the tenant member directory joined by the quote/BOM dashboard
        // chart queries purely for display names (unique pid against tenant-scoped anchors).
        // It must resolve as a platform reference source like ab_user, otherwise every
        // chart-data request is denied for business roles (107 gate 2026-09-20: 1004+ denied
        // executions, all quote/BOM role journeys rendering "Access forbidden").
        when(mapper.findCurrentForTenant(42L)).thenReturn(List.of());
        Map<String, String> resolved = resolve("public.ab_tenant_member");
        assertTrue(resolved.containsKey("\"public\".\"ab_tenant_member\""),
                "ab_tenant_member should resolve as a platform reference source");
        assertEquals(NamedQuerySourceModels.PLATFORM_REFERENCE_MARKER,
                resolved.get("\"public\".\"ab_tenant_member\""));
    }
    @Test void platformFileAndAsyncTaskTablesResolveAsSystemSources() {
        // ab_file (attachments) and ab_async_task carry tenant_id and the quoting named
        // queries filter them explicitly by #{params.tenantId} / the anchor's tenant, so
        // they resolve as platform reference sources even without meta models — otherwise
        // the quote materials overview and recompute-status charts are denied on every
        // Flyway-clean database (fresh-seed red cluster 2026-09-20).
        when(mapper.findCurrentForTenant(42L)).thenReturn(List.of());
        for (String table : List.of("public.ab_file", "public.ab_async_task")) {
            Map<String, String> resolved = resolve(table);
            String key = "\"" + table.substring("public.".length()) + "\"";
            assertTrue(resolved.containsKey("\"public\"." + key),
                    table + " should resolve as a platform reference source");
            assertEquals(NamedQuerySourceModels.PLATFORM_REFERENCE_MARKER,
                    resolved.get("\"public\"." + key));
        }
    }
    @Test void platformRbacTablesResolveAsSystemSources() {
        // ab_user_role / ab_role_permission / ab_permission (owner security sign-off
        // 2026-09-20): the people-workload named queries join all three with explicit
        // tenant_id = anchor-tenant filters; without this admission the workload tools
        // are denied on every Flyway-clean database.
        when(mapper.findCurrentForTenant(42L)).thenReturn(List.of());
        when(jdbc.queryForMap(anyString(), anyString())).thenReturn(
                Map.of("kind", "r", "tenant_column", true, "definition", ""));
        for (String table : List.of("public.ab_user_role", "public.ab_role_permission",
                "public.ab_permission")) {
            Map<String, String> resolved = resolve(table);
            String key = "\"public\".\"" + table.substring("public.".length()) + "\"";
            assertTrue(resolved.containsKey(key), table + " should resolve as a platform reference source");
            assertEquals(NamedQuerySourceModels.PLATFORM_REFERENCE_MARKER, resolved.get(key));
        }
    }

    @Test void platformNamedQueryTableResolvesAsTenantScopedSystemSource() {
        when(mapper.findCurrentForTenant(42L)).thenReturn(List.of());
        Map<String, String> resolved = resolve("public.ab_named_query");
        String model = resolved.get("\"public\".\"ab_named_query\"");
        assertNotNull(model);
        assertTrue(model.startsWith(NamedQuerySourceModels.ENGINE_SOURCE_MARKER_PREFIX));
        assertNotEquals(NamedQuerySourceModels.PLATFORM_REFERENCE_MARKER, model);
    }

    @Test void productTablesRequireApplicationOwnedBypassConfiguration() {
        when(mapper.findCurrentForTenant(42L)).thenReturn(List.of());
        when(jdbc.queryForMap(anyString(), anyString())).thenReturn(
                Map.of("kind", "r", "tenant_column", true, "definition", ""));
        assertThrows(AccessDeniedException.class, () -> resolve("public.product_engine_job"));

        org.springframework.test.util.ReflectionTestUtils.setField(
                resolver, "tenantBypassTablePrefixes", "se_,product_engine_");
        Map<String, String> resolved = resolve("public.product_engine_job");
        assertEquals("engine.product_engine_job", resolved.get("\"public\".\"product_engine_job\""));
    }
    @Test void activePluginNativeSourceRequiresAnExactIdentityAndTenantColumn() {
        when(plugins.getExtensionsOfType(com.auraboot.framework.plugin.extension.NamedQueryNativeSourceExtension.class))
                .thenReturn(List.of(() -> List.of(new com.auraboot.framework.plugin.extension.NamedQueryNativeSourceExtension.Source("public.product_audit", "product.audit"))));
        assertEquals("native.product.audit", resolve("public.product_audit").get(identity("product_audit")));
        assertThrows(AccessDeniedException.class, () -> resolve("private.product_audit"));
        assertThrows(AccessDeniedException.class, () -> resolve("public.product_audit_shadow"));
        when(jdbc.queryForMap(anyString(), anyString())).thenReturn(Map.of("kind", "r", "tenant_column", false));
        assertThrows(AccessDeniedException.class, () -> resolve("public.product_audit"));
    }
    @Test void ambiguousNativeRegistrationFailsClosed() {
        com.auraboot.framework.plugin.extension.NamedQueryNativeSourceExtension owner =
                () -> List.of(new com.auraboot.framework.plugin.extension.NamedQueryNativeSourceExtension.Source("public.product_audit", "product.audit"));
        when(plugins.getExtensionsOfType(com.auraboot.framework.plugin.extension.NamedQueryNativeSourceExtension.class)).thenReturn(List.of(owner, owner));
        assertThrows(AccessDeniedException.class, () -> resolve("public.product_audit"));
    }
    @Test void stoppedPluginCannotLeaveAnAdmittedNativeSource() {
        when(plugins.getExtensionsOfType(com.auraboot.framework.plugin.extension.NamedQueryNativeSourceExtension.class))
                .thenReturn(List.of(() -> List.of(new com.auraboot.framework.plugin.extension.NamedQueryNativeSourceExtension.Source("public.product_audit", "product.audit"))))
                .thenReturn(List.of());
        assertEquals("native.product.audit", resolve("product_audit").get(identity("product_audit")));
        assertThrows(AccessDeniedException.class, () -> resolve("product_audit"));
    }

}
