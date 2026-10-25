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

    @Autowired private com.auraboot.framework.plugin.service.impl.PluginPublicPublicationLifecycle publicLifecycle;
    @Autowired private com.auraboot.framework.openplatform.service.OpenApiPublicationService publications;
    @Autowired private com.auraboot.framework.openplatform.mapper.OpenApiPublicationMapper publicationMapper;
    @Autowired private com.auraboot.framework.meta.service.SchemaManagementService schemas;
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
        assertThat(pluginResourceMapper.findCreatedResourcesForRollback(testTenant.getId(), pluginPid, resource.getImportId()).getFirst().getImportSnapshot()).isEqualTo(snapshot);
        assertThat(pluginResourceMapper.findCreatedResourcesForRollback(testTenant.getId(), pluginPid, "another-import")).isEmpty();
        assertThat(pluginResourceMapper.findCreatedResourcesForRollback(-1L, pluginPid, resource.getImportId())).isEmpty();
        assertThat(pluginResourceMapper.findCreatedResourcesForRollback(testTenant.getId(), "another-plugin", resource.getImportId())).isEmpty();
        assertThat(pluginResourceMapper.findByTenantPluginAndResource(testTenant.getId(), pluginPid, ResourceType.MODEL.code(), resourceCode)
                .getImportSnapshot()).isEqualTo(snapshot);

        Map<String, Object> previous = Map.of("displayName", "Previous value", "metadata", Map.of("version", 1));
        resource.setAction("update");
        resource.setPreviousState(previous);
        resource.setUserModified(true);
        resource.setOwnershipType(OwnershipType.USER_CLAIMED.code());
        assertThat(pluginResourceMapper.updateById(resource)).isEqualTo(1);
        assertThat(pluginResourceMapper.findUpdatedResourcesForRollback(testTenant.getId(), pluginPid, resource.getImportId()).getFirst().getPreviousState()).isEqualTo(previous);
        assertThat(pluginResourceMapper.findUpdatedResourcesForRollback(testTenant.getId(), pluginPid, "another-import")).isEmpty();
        assertThat(pluginResourceMapper.findUpdatedResourcesForRollback(-1L, pluginPid, resource.getImportId())).isEmpty();
        assertThat(pluginResourceMapper.findUpdatedResourcesForRollback(testTenant.getId(), "another-plugin", resource.getImportId())).isEmpty();
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
    private PluginRecord publicPlugin(String suffix) {
        var manifest = PluginManifest.builder().pluginId("com.example.public-" + suffix)
                .namespace("public_" + suffix).version("1.0.0").displayName("Public plugin").build();
        var plugin = PluginRecord.builder().pid(UniqueIdGenerator.generate()).tenantId(testTenant.getId())
                .pluginId(manifest.getPluginId()).namespace(manifest.getNamespace()).version(manifest.getVersion())
                .displayName(manifest.getDisplayName()).manifest(manifest).status("installed").deletedFlag(false).build();
        assertThat(pluginRecordMapper.insert(plugin)).isEqualTo(1);
        return plugin;
    }

    private com.auraboot.framework.openplatform.service.OpenApiResourceContract publicContract(String suffix) {
        String model = "public_plugin_" + suffix;
        models.saveDefinition(ModelDefinition.builder().code(model).displayName("Public lifecycle source")
                .status("published").sourceType("physical").tableName("mt_" + model).primaryKey("id")
                .capabilities(com.auraboot.framework.meta.dto.ModelCapabilities.fullPhysical())
                .fields(java.util.List.of()).build());
        assertThat(schemas.createTableByModel(model).isSuccess()).isTrue();
        var pid = new com.auraboot.framework.openplatform.service.OpenApiResourceContract.Field("pid",
                com.auraboot.framework.openplatform.service.OpenApiResourceContract.Type.PID,
                true, false, false, false, java.util.Set.of(), false, null, Map.of(), null);
        var get = com.auraboot.framework.openplatform.service.OpenApiResourceContract.Operation.GET;
        var list = com.auraboot.framework.openplatform.service.OpenApiResourceContract.Operation.LIST;
        return new com.auraboot.framework.openplatform.service.OpenApiResourceContract("public." + suffix, model, 1,
                java.util.Set.of(get, list), Map.of(get, "public." + suffix + ".get", list, "public." + suffix + ".list"),
                com.auraboot.framework.openplatform.service.OpenApiResourceContract.DataPolicy.INSTALLATION_FILTER,
                Map.of("pid", pid), 25, 4, 1000, java.util.Set.of());
    }

    private com.auraboot.framework.plugin.dto.imports.PluginManifestExtended publicManifest(PluginRecord plugin,
            com.auraboot.framework.openplatform.service.OpenApiResourceContract contract, String version) {
        var manifest = new com.auraboot.framework.plugin.dto.imports.PluginManifestExtended();
        manifest.setPluginId(plugin.getPluginId()); manifest.setNamespace(plugin.getNamespace()); manifest.setVersion(version);
        manifest.setPublicResources(java.util.List.of(contract));
        return manifest;
    }

    private void importPublic(PluginRecord plugin, com.auraboot.framework.plugin.dto.imports.PluginManifestExtended manifest,
                              com.auraboot.framework.plugin.dto.imports.ImportRequest.ConflictStrategy strategy) {
        publicLifecycle.importDeclarations(manifest,
                com.auraboot.framework.plugin.dto.imports.ImportRequest.builder().conflictStrategy(strategy).build(),
                new com.auraboot.framework.plugin.dto.imports.ImportExecuteResult(), plugin.getPid(),
                UniqueIdGenerator.generate(), testTenant.getId());
    }

    @Test
    void publicPluginSnapshotsProtectUserEditsAndRetainWithdrawnOwnership() {
        grantCommittedPermissionToTestRole("sys.connector.update", "system", "connector", "update", "Manage publications");
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        var plugin = publicPlugin(suffix);
        var contract = publicContract(suffix);
        var manifest = publicManifest(plugin, contract, "1.0.0");
        var safe = com.auraboot.framework.plugin.dto.imports.ImportRequest.ConflictStrategy.OVERWRITE_SAFE;
        importPublic(plugin, manifest, safe);
        assertThat(publications.active(testTenant.getId(), contract.code())).contains(contract);
        var owned = pluginResourceMapper.findByTypeAndCode(testTenant.getId(), ResourceType.PUBLIC_RESOURCE.code(), contract.code());
        assertThat(owned.getResourcePid()).isNull();
        assertThat(owned.getLastSyncVersion()).isEqualTo("1.0.0");
        var exported = pluginResourceService.exportPluginConfig(plugin.getPluginId());
        assertThat(exported).containsOnlyKeys("publicResources");
        assertThat(exported.get("publicResources")).singleElement().satisfies(declaration -> {
            assertThat(declaration).containsEntry("code", contract.code()).doesNotContainKeys("draft", "draftRevision", "activeVersion", "status");
        });
        importPublic(plugin, manifest, safe);
        assertThat(publications.snapshots(testTenant.getId(), contract.code())).hasSize(1);
        assertThat(publicationMapper.lock(testTenant.getId(), contract.code()).draftRevision()).isEqualTo(1);
        var revised = new com.auraboot.framework.openplatform.service.OpenApiResourceContract(contract.code(), contract.modelCode(), 2,
                contract.operations(), contract.scopes(), contract.dataPolicy(), contract.fields(), 30, 4, 1000, java.util.Set.of());
        importPublic(plugin, publicManifest(plugin, revised, "2.0.0"), safe);
        assertThat(publications.active(testTenant.getId(), contract.code())).contains(revised);
        owned = pluginResourceMapper.findByTypeAndCode(testTenant.getId(), ResourceType.PUBLIC_RESOURCE.code(), contract.code());
        assertThat(owned.getPreviousState()).containsKeys("publication", "tracking");
        publicLifecycle.restore(owned);
        assertThat(publications.active(testTenant.getId(), contract.code())).contains(contract);
        assertThat(publications.snapshots(testTenant.getId(), contract.code())).hasSize(2);
        assertThat(publicationMapper.lock(testTenant.getId(), contract.code()).draftRevision()).isEqualTo(3);
        var restored = pluginResourceMapper.findByTypeAndCode(testTenant.getId(), ResourceType.PUBLIC_RESOURCE.code(), contract.code());
        assertThat(restored.getLastSyncVersion()).isEqualTo("1.0.0");
        assertThat(pluginResourceService.detectModifications(testTenant.getId(), ResourceType.PUBLIC_RESOURCE, contract.code())).isEmpty();

        var uninstall = pluginResourceService.executeUninstall(plugin.getPid(), testTenant.getId(), UninstallRequest.builder().build());
        assertThat(uninstall.isSuccess()).isTrue();
        assertThat(publications.active(testTenant.getId(), contract.code())).isEmpty();
        assertThat(publications.snapshots(testTenant.getId(), contract.code())).hasSize(2);
        assertThat(pluginResourceMapper.findByTypeAndCode(testTenant.getId(), ResourceType.PUBLIC_RESOURCE.code(), contract.code()))
                .extracting(PluginResource::getAction).isEqualTo("delete");
        importPublic(plugin, manifest, safe);
        assertThat(publications.active(testTenant.getId(), contract.code())).contains(contract);
        assertThat(publications.snapshots(testTenant.getId(), contract.code())).hasSize(2);

        var userDraft = publications.saveDraft(contract.code(),
                new com.auraboot.framework.openplatform.service.OpenApiPublicationService.DraftRequest(3, revised));
        assertThat(pluginResourceService.generateUninstallPreview(plugin.getPid(), testTenant.getId()).getNeedsDecision())
                .extracting("code").containsExactly(contract.code());
        importPublic(plugin, manifest, safe);
        assertThat(publicationMapper.lock(testTenant.getId(), contract.code()).draftRevision()).isEqualTo(userDraft.draftRevision());
        assertThat(catchThrowable(() -> importPublic(plugin, manifest,
                com.auraboot.framework.plugin.dto.imports.ImportRequest.ConflictStrategy.OVERWRITE)))
                .isInstanceOf(com.auraboot.framework.plugin.exception.PluginException.class).hasMessageContaining("modified");
        var detached = pluginResourceService.executeUninstall(plugin.getPid(), testTenant.getId(),
                UninstallRequest.builder().decisions(Map.of(contract.code(), UninstallDecision.KEEP_AND_DETACH)).build());
        assertThat(detached.getDetachedCount()).isEqualTo(1);
        assertThat(publications.active(testTenant.getId(), contract.code())).contains(contract);
        assertThat(pluginResourceMapper.findByTypeAndCode(testTenant.getId(), ResourceType.PUBLIC_RESOURCE.code(), contract.code())
                .getOwnershipTypeEnum()).isEqualTo(OwnershipType.USER_CLAIMED);
    }

    @Test
    void publicQueryFailureRollsBackEarlierPublicationAndOwnership() {
        grantCommittedPermissionToTestRole("sys.connector.update", "system", "connector", "update", "Manage publications");
        String suffix = UniqueIdGenerator.generate().toLowerCase();
        var plugin = publicPlugin(suffix); var contract = publicContract(suffix);
        var manifest = publicManifest(plugin, contract, "1.0.0");
        var query = new com.auraboot.framework.openplatform.service.OpenApiQueryContract("invalid." + suffix,
                "missing_query_" + suffix, 1, "0".repeat(64), Map.of("execute", "public." + suffix + ".query"),
                Map.of(contract.modelCode(), contract.code()), Map.of(), contract.fields(), 25, 100, 1000, 2);
        manifest.setPublicQueries(java.util.List.of(query));
        var nested = new TransactionTemplate(transactions); nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        assertThat(catchThrowable(() -> nested.executeWithoutResult(status -> importPublic(plugin, manifest,
                com.auraboot.framework.plugin.dto.imports.ImportRequest.ConflictStrategy.OVERWRITE_SAFE))))
                .isInstanceOf(MetaServiceException.class).hasMessageContaining("Named query not found: " + query.queryCode());
        assertThat(publicationMapper.lock(testTenant.getId(), contract.code())).isNull();
        assertThat(publicationMapper.lock(testTenant.getId(), query.storageCode())).isNull();
        assertThat(pluginResourceMapper.countByPluginPid(plugin.getPid())).isZero();
        assertThat(publications.snapshots(testTenant.getId(), contract.code())).isEmpty();
    }

}
