package com.auraboot.framework.plugin.marketplace.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePluginDTO;
import com.auraboot.framework.plugin.marketplace.entity.MarketplacePlugin;
import com.auraboot.framework.plugin.marketplace.entity.MarketplaceVersion;
import com.auraboot.framework.plugin.marketplace.mapper.MarketplaceCategoryMapper;
import com.auraboot.framework.plugin.marketplace.mapper.MarketplaceInstallMapper;
import com.auraboot.framework.plugin.marketplace.mapper.MarketplacePluginMapper;
import com.auraboot.framework.plugin.marketplace.mapper.MarketplaceVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketplaceBrowseServiceTest {

    @Mock private MarketplacePluginMapper pluginMapper;
    @Mock private MarketplaceVersionMapper versionMapper;
    @Mock private MarketplaceInstallMapper installMapper;
    @Mock private MarketplaceCategoryMapper categoryMapper;
    @Mock private PluginRecordMapper pluginRecordMapper;

    private MarketplaceBrowseService service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(42L, 99L, "USER-PID", "admin");
        service = new MarketplaceBrowseService(
                pluginMapper,
                versionMapper,
                installMapper,
                categoryMapper,
                pluginRecordMapper,
                new ObjectMapper()
        );
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void getUpgradesTreatsEnterpriseManifestReplacesAsUpgradeFromOssTemplate() {
        MarketplacePlugin enterprise = marketplacePlugin(
                "MP-ASSET",
                "com.auraboot.asset-management",
                "asset",
                "2.0.0"
        );
        when(pluginMapper.findPublished()).thenReturn(List.of(enterprise));
        when(installMapper.findByTenant(42L)).thenReturn(List.of());
        when(pluginRecordMapper.findByTenant()).thenReturn(List.of(pluginRecord(
                "com.auraboot.template.asset-management",
                "tasset",
                "1.0.0"
        )));
        when(categoryMapper.findAll()).thenReturn(List.of());
        when(versionMapper.findLatestPublished("MP-ASSET")).thenReturn(marketplaceVersion(
                "MP-ASSET",
                "2.0.0",
                """
                {
                  "pluginId": "com.auraboot.asset-management",
                  "namespace": "asset",
                  "version": "2.0.0",
                  "upgradesFrom": ["com.auraboot.template.asset-management"],
                  "replaces": ["com.auraboot.template.asset-management"]
                }
                """
        ));

        List<MarketplacePluginDTO> upgrades = service.getUpgrades();

        assertThat(upgrades).hasSize(1);
        MarketplacePluginDTO upgrade = upgrades.get(0);
        assertThat(upgrade.getPluginId()).isEqualTo("com.auraboot.asset-management");
        assertThat(upgrade.getInstalled()).isTrue();
        assertThat(upgrade.getInstalledVersion()).isEqualTo("1.0.0");
        assertThat(upgrade.getLatestVersion()).isEqualTo("2.0.0");
    }

    @Test
    void getUpgradesDoesNotKeepReportingReplacementAfterEnterprisePluginIsInstalled() {
        MarketplacePlugin enterprise = marketplacePlugin(
                "MP-ASSET",
                "com.auraboot.asset-management",
                "asset",
                "2.0.0"
        );
        when(pluginMapper.findPublished()).thenReturn(List.of(enterprise));
        when(installMapper.findByTenant(42L)).thenReturn(List.of());
        when(pluginRecordMapper.findByTenant()).thenReturn(List.of(
                pluginRecord("com.auraboot.template.asset-management", "tasset", "1.0.0"),
                pluginRecord("com.auraboot.asset-management", "asset", "2.0.0")
        ));
        when(categoryMapper.findAll()).thenReturn(List.of());

        List<MarketplacePluginDTO> upgrades = service.getUpgrades();

        assertThat(upgrades).isEmpty();
    }

    private MarketplacePlugin marketplacePlugin(String pid, String pluginId, String namespace, String version) {
        return MarketplacePlugin.builder()
                .pid(pid)
                .pluginId(pluginId)
                .namespace(namespace)
                .displayName(pluginId)
                .status("published")
                .latestVersion(version)
                .licenseMode("free")
                .build();
    }

    private MarketplaceVersion marketplaceVersion(String pluginPid, String version, String manifestSnapshot) {
        return MarketplaceVersion.builder()
                .pid("VER-" + version)
                .marketplacePluginPid(pluginPid)
                .version(version)
                .manifestSnapshot(manifestSnapshot)
                .status("published")
                .build();
    }

    private PluginRecord pluginRecord(String pluginId, String namespace, String version) {
        PluginRecord record = new PluginRecord();
        record.setPid("PLUGIN-" + pluginId);
        record.setPluginId(pluginId);
        record.setNamespace(namespace);
        record.setVersion(version);
        record.setStatus("enabled");
        return record;
    }
}
