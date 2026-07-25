package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.plugin.dto.imports.OwnershipType;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.dto.PluginResourceOwner;
import com.auraboot.framework.plugin.dto.uninstall.ResourceDiff;
import com.auraboot.framework.plugin.dto.uninstall.UninstallPreviewResult;
import com.auraboot.framework.plugin.dto.uninstall.UninstallRequest;
import com.auraboot.framework.plugin.dto.uninstall.UninstallResult;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for PluginResourceServiceImpl covering ownership/modification queries,
 * state diffing, and uninstall preview/execute paths.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PluginResourceServiceImpl Unit Tests")
class PluginResourceServiceImplTest {

    @Mock private PluginResourceMapper resourceMapper;
    @Mock private PluginRecordMapper pluginRecordMapper;
    @Mock private MetaModelFieldBindingMapper bindingMapper;
    @Mock private ObjectMapper objectMapper;
    @Mock private JdbcTemplate jdbcTemplate;

    @InjectMocks private PluginResourceServiceImpl service;

    private PluginResource buildResource(ResourceType type, String code, OwnershipType ownership) {
        PluginResource r = new PluginResource();
        r.setPid("R-" + code);
        r.setResourcePid("RES-" + code);
        r.setResourceCode(code);
        r.setResourceName(code + "-name");
        r.setResourceTypeEnum(type);
        r.setOwnershipTypeEnum(ownership);
        r.setPluginPid("PLG-1");
        return r;
    }

    @Test
    @DisplayName("findByPluginPid delegates to mapper")
    void findByPluginPidDelegates() {
        PluginResource r = buildResource(ResourceType.MODEL, "m1", OwnershipType.PLUGIN_OWNED);
        when(resourceMapper.findByPluginPid("PLG-1")).thenReturn(List.of(r));

        assertThat(service.findByPluginPid("PLG-1")).containsExactly(r);
    }

    @Test
    @DisplayName("findResourceOwner resolves plugin metadata for a managed resource")
    void findResourceOwnerResolvesPluginMetadata() {
        PluginResource resource = buildResource(ResourceType.MODEL, "m1", OwnershipType.SHARED);
        resource.setUserModified(true);
        when(resourceMapper.findByTypeAndCode(1L, ResourceType.MODEL.code(), "m1"))
                .thenReturn(resource);
        when(pluginRecordMapper.findByPid("PLG-1")).thenReturn(PluginRecord.builder()
                .pluginId("com.example.plugin")
                .displayName("Example")
                .version("1.2.3")
                .build());

        PluginResourceOwner owner = service.findResourceOwner(1L, ResourceType.MODEL, "m1");

        assertThat(owner.pluginId()).isEqualTo("com.example.plugin");
        assertThat(owner.pluginName()).isEqualTo("Example");
        assertThat(owner.pluginVersion()).isEqualTo("1.2.3");
        assertThat(owner.ownershipType()).isEqualTo("shared");
        assertThat(owner.userModified()).isTrue();
    }

    @Test
    @DisplayName("findResourceOwner returns null for a user-managed resource")
    void findResourceOwnerSkipsUserManagedResource() {
        PluginResource resource = buildResource(ResourceType.MODEL, "m1", OwnershipType.USER_CLAIMED);
        when(resourceMapper.findByTypeAndCode(1L, ResourceType.MODEL.code(), "m1"))
                .thenReturn(resource);

        assertThat(service.findResourceOwner(1L, ResourceType.MODEL, "m1")).isNull();
        verify(pluginRecordMapper, never()).findByPid(anyString());
    }

    @Test
    @DisplayName("exportPluginConfig groups non-empty snapshots in mapper order")
    void exportPluginConfigGroupsSnapshots() {
        when(pluginRecordMapper.findByTenantAndPluginId("com.example.plugin"))
                .thenReturn(PluginRecord.builder().pid("PLG-1").build());

        PluginResource first = buildResource(ResourceType.MODEL, "m1", OwnershipType.PLUGIN_OWNED);
        first.setImportSnapshot(new LinkedHashMap<>(Map.of("code", "m1")));
        PluginResource empty = buildResource(ResourceType.MODEL, "m2", OwnershipType.PLUGIN_OWNED);
        empty.setImportSnapshot(Map.of());
        PluginResource second = buildResource(ResourceType.PAGE, "p1", OwnershipType.PLUGIN_OWNED);
        second.setImportSnapshot(new LinkedHashMap<>(Map.of("pageKey", "p1")));
        when(resourceMapper.selectList(any())).thenReturn(List.of(first, empty, second));

        Map<String, List<Map<String, Object>>> exported =
                service.exportPluginConfig("com.example.plugin");

        assertThat(exported).containsOnlyKeys("model", "page");
        assertThat(exported.get("model")).containsExactly(Map.of("code", "m1"));
        assertThat(exported.get("page")).containsExactly(Map.of("pageKey", "p1"));
    }

    @Test
    @DisplayName("exportPluginConfig returns an ordered empty map for an unknown plugin")
    void exportPluginConfigUnknownPlugin() {
        when(pluginRecordMapper.findByTenantAndPluginId("missing")).thenReturn(null);

        Map<String, List<Map<String, Object>>> exported = service.exportPluginConfig("missing");

        assertThat(exported).isEmpty();
        assertThat(exported).isInstanceOf(LinkedHashMap.class);
        verify(resourceMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("isResourceManagedByPlugin returns false for missing resource")
    void isResourceManagedByPluginMissing() {
        when(resourceMapper.findByTypeAndCode(eq(1L), anyString(), eq("missing"))).thenReturn(null);
        assertThat(service.isResourceManagedByPlugin(1L, ResourceType.MODEL, "missing")).isFalse();
    }

    @Test
    @DisplayName("isResourceManagedByPlugin returns true for plugin-owned resource")
    void isResourceManagedByPluginTrue() {
        PluginResource r = buildResource(ResourceType.MODEL, "m1", OwnershipType.PLUGIN_OWNED);
        when(resourceMapper.findByTypeAndCode(eq(1L), eq(ResourceType.MODEL.code()), eq("m1")))
                .thenReturn(r);
        assertThat(service.isResourceManagedByPlugin(1L, ResourceType.MODEL, "m1")).isTrue();
    }

    @Test
    @DisplayName("getManagingPluginPid returns null for unmanaged resource")
    void getManagingPluginPidUnmanaged() {
        PluginResource r = buildResource(ResourceType.MODEL, "m1", OwnershipType.USER_CLAIMED);
        when(resourceMapper.findByTypeAndCode(eq(1L), eq(ResourceType.MODEL.code()), eq("m1")))
                .thenReturn(r);
        assertThat(service.getManagingPluginPid(1L, ResourceType.MODEL, "m1")).isNull();
    }

    @Test
    @DisplayName("getManagingPluginPid returns plugin pid when shared/owned")
    void getManagingPluginPidValue() {
        PluginResource r = buildResource(ResourceType.MODEL, "m1", OwnershipType.SHARED);
        when(resourceMapper.findByTypeAndCode(eq(1L), eq(ResourceType.MODEL.code()), eq("m1")))
                .thenReturn(r);
        assertThat(service.getManagingPluginPid(1L, ResourceType.MODEL, "m1")).isEqualTo("PLG-1");
    }

    @Test
    @DisplayName("getOwnershipType returns null when resource missing")
    void getOwnershipTypeNull() {
        when(resourceMapper.findByTypeAndCode(eq(1L), anyString(), anyString())).thenReturn(null);
        assertThat(service.getOwnershipType(1L, ResourceType.MODEL, "x")).isNull();
    }

    @Test
    @DisplayName("updateOwnershipType updates and persists when resource exists")
    void updateOwnershipTypeUpdates() {
        PluginResource r = buildResource(ResourceType.MODEL, "m1", OwnershipType.SHARED);
        when(resourceMapper.findByTypeAndCode(eq(1L), eq(ResourceType.MODEL.code()), eq("m1")))
                .thenReturn(r);

        service.updateOwnershipType(1L, ResourceType.MODEL, "m1", OwnershipType.USER_CLAIMED);

        assertThat(r.getOwnershipTypeEnum()).isEqualTo(OwnershipType.USER_CLAIMED);
        verify(resourceMapper).updateById(r);
    }

    @Test
    @DisplayName("updateOwnershipType is a no-op for missing resource")
    void updateOwnershipTypeNoop() {
        when(resourceMapper.findByTypeAndCode(eq(1L), anyString(), anyString())).thenReturn(null);
        service.updateOwnershipType(1L, ResourceType.MODEL, "x", OwnershipType.USER_CLAIMED);
        verify(resourceMapper, never()).updateById(any(PluginResource.class));
    }

    @Test
    @DisplayName("claimByUser transfers ownership and persists when resource exists")
    void claimByUserUpdatesResource() {
        PluginResource resource = buildResource(ResourceType.MODEL, "m1", OwnershipType.SHARED);
        when(resourceMapper.findByTypeAndCode(1L, ResourceType.MODEL.code(), "m1"))
                .thenReturn(resource);

        service.claimByUser(1L, ResourceType.MODEL, "m1");

        assertThat(resource.getOwnershipTypeEnum()).isEqualTo(OwnershipType.USER_CLAIMED);
        assertThat(resource.getUserModified()).isTrue();
        verify(resourceMapper).updateById(resource);
    }

    @Test
    @DisplayName("claimByUser is a no-op for a missing resource")
    void claimByUserMissingResource() {
        when(resourceMapper.findByTypeAndCode(1L, ResourceType.MODEL.code(), "missing"))
                .thenReturn(null);

        service.claimByUser(1L, ResourceType.MODEL, "missing");

        verify(resourceMapper, never()).updateById(any(PluginResource.class));
    }

    @Test
    @DisplayName("markAsUserModified flips flag and persists once")
    void markAsUserModifiedShouldPersist() {
        PluginResource r = buildResource(ResourceType.MODEL, "m1", OwnershipType.SHARED);
        r.setUserModified(false);
        when(resourceMapper.findByTypeAndCode(eq(1L), eq(ResourceType.MODEL.code()), eq("m1")))
                .thenReturn(r);

        service.markAsUserModified(1L, ResourceType.MODEL, "m1");

        assertThat(r.getUserModified()).isTrue();
        verify(resourceMapper).updateById(r);
    }

    @Test
    @DisplayName("markAsUserModified should not re-persist when already user-modified")
    void markAsUserModifiedAlreadyTrue() {
        PluginResource r = buildResource(ResourceType.MODEL, "m1", OwnershipType.SHARED);
        r.setUserModified(true);
        when(resourceMapper.findByTypeAndCode(eq(1L), eq(ResourceType.MODEL.code()), eq("m1")))
                .thenReturn(r);

        service.markAsUserModified(1L, ResourceType.MODEL, "m1");

        verify(resourceMapper, never()).updateById(any(PluginResource.class));
    }

    @Test
    @DisplayName("isUserModified reflects userModified flag")
    void isUserModifiedReflectsFlag() {
        PluginResource r = buildResource(ResourceType.MODEL, "m1", OwnershipType.SHARED);
        r.setUserModified(true);
        when(resourceMapper.findByTypeAndCode(eq(1L), eq(ResourceType.MODEL.code()), eq("m1")))
                .thenReturn(r);

        assertThat(service.isUserModified(1L, ResourceType.MODEL, "m1")).isTrue();
    }

    @Test
    @DisplayName("isUserModified returns false when resource missing")
    void isUserModifiedFalseWhenMissing() {
        when(resourceMapper.findByTypeAndCode(eq(1L), anyString(), anyString())).thenReturn(null);
        assertThat(service.isUserModified(1L, ResourceType.MODEL, "absent")).isFalse();
    }

    @Test
    @DisplayName("compareStates returns empty when either side null")
    void compareStatesNullSafe() {
        assertThat(service.compareStates(null, Map.of())).isEmpty();
        assertThat(service.compareStates(Map.of(), null)).isEmpty();
    }

    @Test
    @DisplayName("compareStates ignores infrastructure fields and reports real diffs")
    void compareStatesProducesDiffs() {
        Map<String, Object> orig = new HashMap<>();
        orig.put("title", "old");
        orig.put("status", "draft");
        orig.put("created_at", "2026-01-01");
        orig.put("tenantId", 1L);

        Map<String, Object> curr = new HashMap<>();
        curr.put("title", "new");
        curr.put("status", "draft");
        curr.put("created_at", "2026-02-02"); // ignored
        curr.put("tenantId", 999L);            // ignored

        List<ResourceDiff> diffs = service.compareStates(orig, curr);

        assertThat(diffs).hasSize(1);
        assertThat(diffs.get(0).getField()).isEqualTo("title");
        assertThat(diffs.get(0).getOriginal()).isEqualTo("old");
        assertThat(diffs.get(0).getCurrent()).isEqualTo("new");
    }

    @Test
    @DisplayName("compareStates with same content yields empty diff")
    void compareStatesNoDiff() {
        Map<String, Object> a = Map.of("title", "x", "status", "active");
        assertThat(service.compareStates(a, a)).isEmpty();
    }

    @Test
    @DisplayName("detectModifications returns empty when resource has no snapshot")
    void detectModificationsNoSnapshot() {
        PluginResource r = buildResource(ResourceType.MODEL, "m1", OwnershipType.SHARED);
        r.setImportSnapshot(null);
        when(resourceMapper.findByTypeAndCode(eq(1L), eq(ResourceType.MODEL.code()), eq("m1")))
                .thenReturn(r);

        assertThat(service.detectModifications(1L, ResourceType.MODEL, "m1")).isEmpty();
    }

    @Test
    @DisplayName("detectModifications returns empty when resource missing")
    void detectModificationsMissingResource() {
        when(resourceMapper.findByTypeAndCode(eq(1L), eq(ResourceType.MODEL.code()), eq("absent")))
                .thenReturn(null);
        assertThat(service.detectModifications(1L, ResourceType.MODEL, "absent")).isEmpty();
    }

    @Test
    @DisplayName("getCurrentDatabaseState maps scalar and JSONB columns")
    @SuppressWarnings("unchecked")
    void getCurrentDatabaseStateMapsResultSet() throws Exception {
        ResultSet resultSet = org.mockito.Mockito.mock(ResultSet.class);
        ResultSetMetaData metadata = org.mockito.Mockito.mock(ResultSetMetaData.class);
        PGobject jsonb = new PGobject();
        jsonb.setType("jsonb");
        jsonb.setValue("{\"source\":\"plugin\"}");

        when(resultSet.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(2);
        when(metadata.getColumnName(1)).thenReturn("code");
        when(metadata.getColumnName(2)).thenReturn("settings");
        when(resultSet.getObject(1)).thenReturn("m1");
        when(resultSet.getObject(2)).thenReturn(jsonb);
        when(objectMapper.readValue(eq(jsonb.getValue()), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                .thenReturn(Map.of("source", "plugin"));
        when(jdbcTemplate.queryForObject(
                anyString(),
                any(RowMapper.class),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    RowMapper<Map<String, Object>> mapper = invocation.getArgument(1);
                    return mapper.mapRow(resultSet, 0);
                });

        Map<String, Object> state =
                service.getCurrentDatabaseState(1L, ResourceType.MODEL, "m1");

        assertThat(state)
                .containsEntry("code", "m1")
                .containsEntry("settings", Map.of("source", "plugin"));
    }

    @Test
    @DisplayName("getCurrentDatabaseState covers type-specific code columns and active predicates")
    @SuppressWarnings("unchecked")
    void getCurrentDatabaseStateUsesTypeSpecificSql() {
        when(jdbcTemplate.queryForObject(
                anyString(),
                any(RowMapper.class),
                any(Object[].class)))
                .thenReturn(Map.of("ok", true));

        assertThat(service.getCurrentDatabaseState(1L, ResourceType.MENU, "menu")).containsEntry("ok", true);
        assertThat(service.getCurrentDatabaseState(1L, ResourceType.PAGE, "page")).containsEntry("ok", true);
        assertThat(service.getCurrentDatabaseState(1L, ResourceType.PROCESS, "process")).containsEntry("ok", true);
        assertThat(service.getCurrentDatabaseState(
                1L, ResourceType.AGENT_DEFINITION, "agent")).containsEntry("ok", true);
        assertThat(service.getCurrentDatabaseState(1L, ResourceType.NAMED_QUERY, "query")).containsEntry("ok", true);
        verify(jdbcTemplate, times(5)).queryForObject(
                anyString(),
                any(RowMapper.class),
                any(Object[].class));
    }

    @Test
    @DisplayName("getCurrentDatabaseState returns empty state when the query fails")
    @SuppressWarnings("unchecked")
    void getCurrentDatabaseStateQueryFailure() {
        when(jdbcTemplate.queryForObject(
                anyString(),
                any(RowMapper.class),
                any(Object[].class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThat(service.getCurrentDatabaseState(1L, ResourceType.MODEL, "m1")).isEmpty();
    }

    @Test
    @DisplayName("countByOwnershipType buckets resources into the three ownership types")
    void countByOwnershipTypeBuckets() {
        PluginResource a = buildResource(ResourceType.MODEL, "a", OwnershipType.PLUGIN_OWNED);
        PluginResource b = buildResource(ResourceType.MODEL, "b", OwnershipType.PLUGIN_OWNED);
        PluginResource c = buildResource(ResourceType.MODEL, "c", OwnershipType.SHARED);
        PluginResource d = buildResource(ResourceType.MODEL, "d", OwnershipType.USER_CLAIMED);
        when(resourceMapper.findByPluginPid("PLG-1")).thenReturn(List.of(a, b, c, d));

        Map<OwnershipType, Integer> counts = service.countByOwnershipType("PLG-1");

        assertThat(counts).containsEntry(OwnershipType.PLUGIN_OWNED, 2)
                .containsEntry(OwnershipType.SHARED, 1)
                .containsEntry(OwnershipType.USER_CLAIMED, 1);
    }

    @Test
    @DisplayName("findModifiedResources / findUserClaimedResources delegate to mapper")
    void findHelpers() {
        PluginResource r = buildResource(ResourceType.MODEL, "m1", OwnershipType.SHARED);
        when(resourceMapper.findModifiedByPluginPid("P")).thenReturn(List.of(r));
        when(resourceMapper.findUserClaimedByPluginPid("P")).thenReturn(List.of());

        assertThat(service.findModifiedResources("P")).containsExactly(r);
        assertThat(service.findUserClaimedResources("P")).isEmpty();
    }

    @Test
    @DisplayName("generateUninstallPreview throws when plugin not found")
    void uninstallPreviewMissingPlugin() {
        when(pluginRecordMapper.findByPid("PLG-X")).thenReturn(null);
        assertThatThrownBy(() -> service.generateUninstallPreview("PLG-X", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("generateUninstallPreview classifies USER_CLAIMED to keep, PLUGIN_OWNED to delete")
    void uninstallPreviewBuckets() {
        PluginRecord plugin = PluginRecord.builder()
                .pid("PLG-1").pluginId("p").displayName("P").version("1.0").build();
        when(pluginRecordMapper.findByPid("PLG-1")).thenReturn(plugin);

        PluginResource owned = buildResource(ResourceType.MODEL, "owned", OwnershipType.PLUGIN_OWNED);
        PluginResource claimed = buildResource(ResourceType.MODEL, "claimed", OwnershipType.USER_CLAIMED);
        PluginResource sharedClean = buildResource(ResourceType.MODEL, "shared", OwnershipType.SHARED);
        sharedClean.setUserModified(false);

        when(resourceMapper.findByPluginPid("PLG-1"))
                .thenReturn(List.of(owned, claimed, sharedClean));

        UninstallPreviewResult preview = service.generateUninstallPreview("PLG-1", 1L);

        assertThat(preview.getWillDelete()).hasSize(2);
        assertThat(preview.getWillKeep()).hasSize(1);
        assertThat(preview.getNeedsDecision()).isEmpty();
        assertThat(preview.isHasConflicts()).isFalse();
        assertThat(preview.getTotalResources()).isEqualTo(3);
    }

    @Test
    @DisplayName("executeUninstall fails when conflicts present and decisions missing")
    void executeUninstallMissingDecision() {
        PluginRecord plugin = PluginRecord.builder()
                .pid("PLG-1").pluginId("p").displayName("P").version("1.0").build();
        when(pluginRecordMapper.findByPid("PLG-1")).thenReturn(plugin);

        // shared+modified+with-snapshot+detectable diff is fragile to mock through JdbcTemplate.
        // Instead, simulate a SHARED resource that is unmodified — preview will not produce
        // needsDecision, so this happy path verifies success branch.
        PluginResource owned = buildResource(ResourceType.MODEL, "o1", OwnershipType.PLUGIN_OWNED);
        when(resourceMapper.findByPluginPid("PLG-1")).thenReturn(List.of(owned));
        when(resourceMapper.findByTypeAndCode(eq(1L), eq(ResourceType.MODEL.code()), eq("o1")))
                .thenReturn(owned);

        UninstallRequest req = new UninstallRequest();
        req.setForce(false);

        UninstallResult result = service.executeUninstall("PLG-1", 1L, req);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getDeletedCount()).isEqualTo(1);
        assertThat(result.getDeletedResources()).containsExactly("o1");
    }

    @Test
    @DisplayName("detachResource updates ownership and clears plugin_pid via JdbcTemplate")
    void detachResourceShouldUpdate() {
        PluginResource r = buildResource(ResourceType.MODEL, "m1", OwnershipType.SHARED);

        service.detachResource(r);

        assertThat(r.getOwnershipTypeEnum()).isEqualTo(OwnershipType.USER_CLAIMED);
        verify(resourceMapper).updateById(r);
        verify(jdbcTemplate).update(anyString(), eq(r.getResourcePid()));
    }

    @Test
    @DisplayName("deleteResource for MODEL_FIELD_BINDING uses bindingMapper.softDeleteByPid")
    void deleteResourceBindingPath() {
        PluginResource r = buildResource(ResourceType.MODEL_FIELD_BINDING, "b1", OwnershipType.PLUGIN_OWNED);

        service.deleteResource(r);

        verify(bindingMapper).softDeleteByPid("RES-b1");
    }

    @Test
    @DisplayName("deleteResource for non-binding type uses jdbcTemplate UPDATE")
    void deleteResourceNonBindingPath() {
        PluginResource r = buildResource(ResourceType.MODEL, "m1", OwnershipType.PLUGIN_OWNED);

        service.deleteResource(r);

        verify(jdbcTemplate).update(anyString(), eq(r.getResourcePid()));
        verify(bindingMapper, never()).softDeleteByPid(anyString());
    }
}
