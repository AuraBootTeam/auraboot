package com.auraboot.framework.plugin.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.PluginManifest;
import com.auraboot.framework.plugin.dto.PluginResourceOwner;
import com.auraboot.framework.plugin.dto.imports.OwnershipType;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.plugin.dto.uninstall.UninstallRequest;
import com.auraboot.framework.plugin.dto.uninstall.UninstallDecision;
import com.auraboot.framework.meta.exception.MetaServiceException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class PluginResourceServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PluginResourceService pluginResourceService;

    @Autowired
    private PluginRecordMapper pluginRecordMapper;

    @Autowired
    private PluginResourceMapper pluginResourceMapper;

    @Autowired private MetaModelService models;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void ownerAndExportReadThroughRealPostgresTypeHandlers() {
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        String pluginPid = UniqueIdGenerator.generate();
        String pluginId = "com.example.resource-" + suffix;
        String namespace = "resource_" + suffix;
        String resourceCode = "model_" + suffix;

        PluginManifest manifest = PluginManifest.builder()
                .pluginId(pluginId)
                .namespace(namespace)
                .version("1.2.3")
                .displayName("Resource IT")
                .build();
        PluginRecord plugin = PluginRecord.builder()
                .pid(pluginPid)
                .tenantId(testTenant.getId())
                .pluginId(pluginId)
                .namespace(namespace)
                .version("1.2.3")
                .displayName("Resource IT")
                .status("installed")
                .manifest(manifest)
                .deletedFlag(false)
                .build();
        assertThat(pluginRecordMapper.insert(plugin)).isEqualTo(1);

        Map<String, Object> snapshot = Map.of(
                "code", resourceCode,
                "displayName", "Imported model",
                "metadata", Map.of("source", "integration-test"));
        PluginResource resource = PluginResource.builder()
                .pid(UniqueIdGenerator.generate())
                .tenantId(testTenant.getId())
                .pluginPid(pluginPid)
                .importId(UniqueIdGenerator.generate())
                .resourceType(ResourceType.MODEL.code())
                .resourceCode(resourceCode)
                .resourceName("Imported model")
                .action("create")
                .ownershipType(OwnershipType.SHARED.code())
                .importSnapshot(snapshot)
                .sequence(10)
                .userModified(false)
                .build();
        assertThat(pluginResourceMapper.insert(resource)).isEqualTo(1);

        PluginResourceOwner owner = pluginResourceService.findResourceOwner(
                testTenant.getId(), ResourceType.MODEL, resourceCode);
        Map<String, java.util.List<Map<String, Object>>> exported =
                pluginResourceService.exportPluginConfig(pluginId);

        assertThat(owner.pluginId()).isEqualTo(pluginId);
        assertThat(owner.pluginName()).isEqualTo("Resource IT");
        assertThat(owner.pluginVersion()).isEqualTo("1.2.3");
        assertThat(owner.ownershipType()).isEqualTo("shared");
        assertThat(exported).containsOnlyKeys("model");
        assertThat(exported.get("model")).containsExactly(snapshot);
        assertThat(pluginResourceService.findByPluginPid(pluginPid).getFirst().getImportSnapshot()).isEqualTo(snapshot);
        assertThat(pluginResourceService.findByTypeAndCode(testTenant.getId(), ResourceType.MODEL, resourceCode)
                .getImportSnapshot()).isEqualTo(snapshot);
        assertThat(pluginResourceMapper.findByImportId(resource.getImportId()).getFirst().getImportSnapshot()).isEqualTo(snapshot);
        assertThat(pluginResourceMapper.findByPluginPidAndType(pluginPid, ResourceType.MODEL.code()).getFirst().getImportSnapshot())
                .isEqualTo(snapshot);
        assertThat(pluginResourceMapper.findByTenantAndType(testTenant.getId(), ResourceType.MODEL.code()))
                .filteredOn(row -> resourceCode.equals(row.getResourceCode())).singleElement()
                .extracting(PluginResource::getImportSnapshot).isEqualTo(snapshot);
        assertThat(pluginResourceMapper.findCreatedResourcesForRollback(pluginPid).getFirst().getImportSnapshot()).isEqualTo(snapshot);
        assertThat(pluginResourceMapper.findByTenantPluginAndResource(testTenant.getId(), pluginPid, ResourceType.MODEL.code(), resourceCode)
                .getImportSnapshot()).isEqualTo(snapshot);

        Map<String, Object> previous = Map.of("displayName", "Previous value", "metadata", Map.of("version", 1));
        resource.setAction("update");
        resource.setPreviousState(previous);
        resource.setUserModified(true);
        resource.setOwnershipType(OwnershipType.USER_CLAIMED.code());
        assertThat(pluginResourceMapper.updateById(resource)).isEqualTo(1);
        assertThat(pluginResourceMapper.findUpdatedResourcesForRollback(pluginPid).getFirst().getPreviousState()).isEqualTo(previous);
        assertThat(pluginResourceMapper.findModifiedByPluginPid(pluginPid).getFirst().getImportSnapshot()).isEqualTo(snapshot);
        assertThat(pluginResourceMapper.findUserClaimedByPluginPid(pluginPid).getFirst().getPreviousState()).isEqualTo(previous);
    }

    @Test
    void failedUninstallRollsBackEarlierMetadataDeletionAndKeepsTracking() {
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        String pluginPid = UniqueIdGenerator.generate();
        String modelCode = "plugin_atomic_" + suffix;
        var manifest = PluginManifest.builder().pluginId("com.example.atomic-" + suffix)
                .namespace("atomic_" + suffix).version("1.0.0").displayName("Atomic uninstall").build();
        var plugin = PluginRecord.builder().pid(pluginPid).tenantId(testTenant.getId())
                .pluginId(manifest.getPluginId()).namespace(manifest.getNamespace()).version(manifest.getVersion())
                .displayName(manifest.getDisplayName()).status("installed").manifest(manifest).deletedFlag(false).build();
        assertThat(pluginRecordMapper.insert(plugin)).isEqualTo(1);
        models.saveDefinition(ModelDefinition.builder().code(modelCode).displayName("Atomic metadata")
                .status("draft").sourceType("physical").tableName("mt_" + modelCode).primaryKey("id")
                .fields(java.util.List.of()).build());
        String modelPid = models.findByCode(modelCode).getPid();
        var owned = PluginResource.builder().pid(UniqueIdGenerator.generate()).tenantId(testTenant.getId())
                .pluginPid(pluginPid).resourceType(ResourceType.MODEL.code()).resourceCode(modelCode)
                .resourcePid(modelPid).resourceName("Atomic metadata").action("create")
                .ownershipType(OwnershipType.PLUGIN_OWNED.code()).importSnapshot(Map.of("code", modelCode))
                .sequence(10).userModified(false).build();
        var modified = PluginResource.builder().pid(UniqueIdGenerator.generate()).tenantId(testTenant.getId())
                .pluginPid(pluginPid).resourceType(ResourceType.MODEL.code()).resourceCode(modelCode + "_modified")
                .resourceName("Modified metadata").action("create").ownershipType(OwnershipType.SHARED.code())
                .importSnapshot(Map.of("display_name", "Imported value")).sequence(20).userModified(true).build();
        assertThat(pluginResourceMapper.insert(owned)).isEqualTo(1);
        assertThat(pluginResourceMapper.insert(modified)).isEqualTo(1);
        var preview = pluginResourceService.generateUninstallPreview(pluginPid, testTenant.getId());
        assertThat(preview.getWillDelete()).extracting("code").containsExactly(modelCode);
        assertThat(preview.getNeedsDecision()).extracting("code").containsExactly(modified.getResourceCode());

        // A Java-side failure occurs after the first real metadata mutation, without a SQL error aborting PostgreSQL.
        Map<String, UninstallDecision> decisions = new java.util.HashMap<>();
        decisions.put(modified.getResourceCode(), null);
        var nested = new TransactionTemplate(transactions);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        Throwable failure = catchThrowable(() -> nested.executeWithoutResult(status ->
                pluginResourceService.executeUninstall(pluginPid, testTenant.getId(),
                        UninstallRequest.builder().decisions(decisions).build())));
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_flag FROM ab_meta_model WHERE pid = ?", Boolean.class, modelPid))
                .as("earlier metadata mutation rolled back").isFalse();
        assertThat(pluginResourceMapper.countByPluginPid(pluginPid)).isEqualTo(2);
        assertThat(failure).isInstanceOf(MetaServiceException.class).hasCauseInstanceOf(NullPointerException.class);

        var success = pluginResourceService.executeUninstall(pluginPid, testTenant.getId(),
                UninstallRequest.builder().decisions(Map.of(modified.getResourceCode(), UninstallDecision.DELETE)).build());
        assertThat(success.isSuccess()).isTrue();
        assertThat(success.getDeletedResources()).containsExactly(modelCode, modified.getResourceCode());
        assertThat(pluginResourceMapper.countByPluginPid(pluginPid)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT deleted_flag FROM ab_meta_model WHERE pid = ?", Boolean.class, modelPid)).isTrue();
    }
}
