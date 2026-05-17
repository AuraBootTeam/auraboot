package com.auraboot.framework.plugin.marketplace.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.entitlement.spi.EntitlementChecker;
import com.auraboot.framework.entitlement.spi.EntitlementProvisioner;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.marketplace.dto.MarketplaceInstallRequest;
import com.auraboot.framework.plugin.marketplace.entity.MarketplaceInstall;
import com.auraboot.framework.plugin.marketplace.entity.MarketplacePlugin;
import com.auraboot.framework.plugin.marketplace.entity.MarketplaceVersion;
import com.auraboot.framework.plugin.marketplace.mapper.MarketplaceCategoryMapper;
import com.auraboot.framework.plugin.marketplace.mapper.MarketplaceInstallMapper;
import com.auraboot.framework.plugin.marketplace.mapper.MarketplacePluginMapper;
import com.auraboot.framework.plugin.marketplace.mapper.MarketplaceVersionMapper;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketplaceInstallServiceTest {

    @Mock private MarketplacePluginMapper pluginMapper;
    @Mock private MarketplaceVersionMapper versionMapper;
    @Mock private MarketplaceInstallMapper installMapper;
    @Mock private MarketplaceCategoryMapper categoryMapper;
    @Mock private PluginImportService pluginImportService;
    @Mock private PluginRecordMapper pluginRecordMapper;
    @Mock private EntitlementChecker entitlementChecker;
    @Mock private EntitlementProvisioner entitlementProvisioner;
    @Mock private MarketplacePaidService marketplacePaidService;

    private MarketplaceInstallService service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(42L, 99L, "USER-PID", "admin");
        service = new MarketplaceInstallService(
                pluginMapper,
                versionMapper,
                installMapper,
                categoryMapper,
                pluginImportService,
                new ObjectMapper(),
                pluginRecordMapper,
                entitlementChecker,
                entitlementProvisioner,
                marketplacePaidService
        );
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void paidPluginInstallRequiresEntitlementOrInstallToken() {
        when(pluginMapper.findByPluginId("paid-plugin")).thenReturn(publishedPlugin("platform"));
        when(installMapper.findByTenantAndPlugin(42L, "MP-PID")).thenReturn(null);
        when(versionMapper.findLatestPublished("MP-PID")).thenReturn(publishedVersion());
        when(entitlementChecker.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.install("paid-plugin", new MarketplaceInstallRequest()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("install token required");

        verify(pluginImportService, never()).executeFromManifest(any(PluginManifestExtended.class), any(ImportRequest.class));
    }

    @Test
    void paidPluginInstallAcceptsMatchingInstallToken() {
        when(pluginMapper.findByPluginId("paid-plugin")).thenReturn(publishedPlugin("vendor"));
        when(installMapper.findByTenantAndPlugin(42L, "MP-PID")).thenReturn(null);
        when(versionMapper.findLatestPublished("MP-PID")).thenReturn(publishedVersion());
        when(entitlementChecker.isEnabled()).thenReturn(false);
        when(pluginImportService.executeFromManifest(any(PluginManifestExtended.class), any(ImportRequest.class)))
                .thenReturn(ImportExecuteResult.success("IMP-PID", "TENANT-PLUGIN-PID", "paid-plugin", "paid", "1.0.0"));

        MarketplaceInstallRequest request = new MarketplaceInstallRequest();
        request.setInstallToken("tok.secret");
        request.setTargetInstanceUrl("http://localhost:5173");

        service.install("paid-plugin", request);

        verify(marketplacePaidService).authorizeInstallTokenForInstall(
                "tok.secret",
                "MP-PID",
                "VER-PID",
                "http://localhost:5173"
        );
        verify(pluginImportService).executeFromManifest(any(PluginManifestExtended.class), any(ImportRequest.class));
        verify(installMapper).insert(any(MarketplaceInstall.class));
    }

    @Test
    void activeEntitlementAllowsPaidInstallWithoutToken() {
        when(pluginMapper.findByPluginId("paid-plugin")).thenReturn(publishedPlugin("platform"));
        when(installMapper.findByTenantAndPlugin(42L, "MP-PID")).thenReturn(null);
        when(versionMapper.findLatestPublished("MP-PID")).thenReturn(publishedVersion());
        when(entitlementChecker.isEnabled()).thenReturn(true);
        when(entitlementChecker.isPluginActive(42L, "paid-plugin")).thenReturn(true);
        when(pluginImportService.executeFromManifest(any(PluginManifestExtended.class), any(ImportRequest.class)))
                .thenReturn(ImportExecuteResult.success("IMP-PID", "TENANT-PLUGIN-PID", "paid-plugin", "paid", "1.0.0"));

        service.install("paid-plugin", new MarketplaceInstallRequest());

        verify(marketplacePaidService, never()).authorizeInstallTokenForInstall(any(), any(), any(), any());
        verify(pluginImportService).executeFromManifest(any(PluginManifestExtended.class), any(ImportRequest.class));
    }

    @Test
    void previewUpgradeRecognizesEnterpriseReplacementForLegacyOssTemplate() {
        when(pluginMapper.findByPluginId("com.auraboot.asset-management"))
                .thenReturn(enterpriseReplacementPlugin());
        when(versionMapper.findLatestPublished("MP-ASSET")).thenReturn(enterpriseReplacementVersion());
        when(installMapper.findByTenantAndPlugin(42L, "MP-ASSET")).thenReturn(null);
        when(pluginRecordMapper.findByTenant()).thenReturn(List.of(pluginRecord(
                "com.auraboot.template.asset-management",
                "tasset",
                "1.0.0"
        )));

        Map<String, Object> preview = service.previewUpgrade("com.auraboot.asset-management");

        assertThat(preview).containsEntry("pluginId", "com.auraboot.asset-management");
        assertThat(preview).containsEntry("upgradeFromPluginId", "com.auraboot.template.asset-management");
        assertThat(preview).containsEntry("installedVersion", "1.0.0");
        assertThat(preview).containsEntry("latestVersion", "2.0.0");
        assertThat(preview).containsEntry("upgradeType", "replacement");
    }

    @Test
    void upgradeInstallsEnterpriseReplacementAndRecordsMarketplaceInstall() {
        when(pluginMapper.findByPluginId("com.auraboot.asset-management"))
                .thenReturn(enterpriseReplacementPlugin());
        when(versionMapper.findLatestPublished("MP-ASSET")).thenReturn(enterpriseReplacementVersion());
        when(installMapper.findByTenantAndPlugin(42L, "MP-ASSET")).thenReturn(null);
        when(pluginRecordMapper.findByTenant()).thenReturn(List.of(pluginRecord(
                "com.auraboot.template.asset-management",
                "tasset",
                "1.0.0"
        )));
        when(entitlementChecker.isEnabled()).thenReturn(false);
        when(pluginImportService.executeFromManifest(any(PluginManifestExtended.class), any(ImportRequest.class)))
                .thenReturn(ImportExecuteResult.success(
                        "IMP-PID",
                        "TENANT-PLUGIN-PID",
                        "com.auraboot.asset-management",
                        "asset",
                        "2.0.0"
                ));

        ImportExecuteResult result = service.upgrade(
                "com.auraboot.asset-management",
                new MarketplaceInstallRequest()
        );

        assertThat(result.isSuccess()).isTrue();
        verify(pluginImportService).executeFromManifest(any(PluginManifestExtended.class), any(ImportRequest.class));
        verify(installMapper).insert(any(MarketplaceInstall.class));
        verify(pluginMapper).incrementInstallCount("MP-ASSET");
    }

    private MarketplacePlugin publishedPlugin(String licenseMode) {
        return MarketplacePlugin.builder()
                .pid("MP-PID")
                .pluginId("paid-plugin")
                .namespace("paid")
                .displayName("Paid Plugin")
                .status("published")
                .licenseMode(licenseMode)
                .build();
    }

    private MarketplacePlugin enterpriseReplacementPlugin() {
        return MarketplacePlugin.builder()
                .pid("MP-ASSET")
                .pluginId("com.auraboot.asset-management")
                .namespace("asset")
                .displayName("Asset Management Enterprise")
                .status("published")
                .licenseMode("free")
                .latestVersion("2.0.0")
                .build();
    }

    private MarketplaceVersion publishedVersion() {
        return MarketplaceVersion.builder()
                .pid("VER-PID")
                .marketplacePluginPid("MP-PID")
                .version("1.0.0")
                .manifestSnapshot("""
                        {
                          "pluginId": "paid-plugin",
                          "namespace": "paid",
                          "version": "1.0.0"
                        }
                        """)
                .status("published")
                .build();
    }

    private MarketplaceVersion enterpriseReplacementVersion() {
        return MarketplaceVersion.builder()
                .pid("VER-ASSET-2")
                .marketplacePluginPid("MP-ASSET")
                .version("2.0.0")
                .manifestSnapshot("""
                        {
                          "pluginId": "com.auraboot.asset-management",
                          "namespace": "asset",
                          "version": "2.0.0",
                          "upgradesFrom": ["com.auraboot.template.asset-management"],
                          "replaces": ["com.auraboot.template.asset-management"]
                        }
                        """)
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
