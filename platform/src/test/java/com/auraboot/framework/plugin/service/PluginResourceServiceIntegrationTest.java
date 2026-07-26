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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PluginResourceServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PluginResourceService pluginResourceService;

    @Autowired
    private PluginRecordMapper pluginRecordMapper;

    @Autowired
    private PluginResourceMapper pluginResourceMapper;

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
    }
}
