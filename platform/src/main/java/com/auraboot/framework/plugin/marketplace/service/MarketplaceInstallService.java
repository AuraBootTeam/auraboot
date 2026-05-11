package com.auraboot.framework.plugin.marketplace.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.entitlement.spi.EntitlementChecker;
import com.auraboot.framework.entitlement.spi.EntitlementProvisioner;
import com.auraboot.framework.saas.executor.SystemTenantContextExecutor;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.marketplace.dto.MarketplaceInstallRequest;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePluginDTO;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.plugin.marketplace.entity.*;
import com.auraboot.framework.plugin.marketplace.mapper.*;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketplaceInstallService {

    private final MarketplacePluginMapper pluginMapper;
    private final MarketplaceVersionMapper versionMapper;
    private final MarketplaceInstallMapper installMapper;
    private final MarketplaceCategoryMapper categoryMapper;
    private final PluginImportService pluginImportService;
    private final ObjectMapper objectMapper;
    private final EntitlementChecker entitlementChecker;
    private final EntitlementProvisioner entitlementProvisioner;
    private final MarketplacePaidService marketplacePaidService;

    @Transactional
    public ImportExecuteResult install(String pluginId, MarketplaceInstallRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new RuntimeException("Tenant context required for installation");
        }

        // Find marketplace plugin — G2 table, system tenant context
        MarketplacePlugin mpPlugin = SystemTenantContextExecutor.executeAsSystem(() -> pluginMapper.findByPluginId(pluginId));
        if (mpPlugin == null) {
            throw new RuntimeException("Plugin not found in marketplace: " + pluginId);
        }
        if (!StatusConstants.PUBLISHED.equals(mpPlugin.getStatus())) {
            throw new RuntimeException("Plugin is not published: " + pluginId);
        }

        // Check if already installed
        MarketplaceInstall existing = installMapper.findByTenantAndPlugin(tenantId, mpPlugin.getPid());
        if (existing != null) {
            throw new RuntimeException("Plugin already installed. Use upgrade instead.");
        }

        // Find version to install — G2 table, system tenant context
        final MarketplacePlugin mpPluginFinal = mpPlugin;
        MarketplaceVersion version = SystemTenantContextExecutor.executeAsSystem(() -> {
            if (request.getVersion() != null && !request.getVersion().isBlank()) {
                MarketplaceVersion v = versionMapper.findByPluginPidAndVersion(mpPluginFinal.getPid(), request.getVersion());
                if (v == null) {
                    throw new RuntimeException("Version not found: " + request.getVersion());
                }
                return v;
            } else {
                MarketplaceVersion v = versionMapper.findLatestPublished(mpPluginFinal.getPid());
                if (v == null) {
                    throw new RuntimeException("No published version available");
                }
                return v;
            }
        });
        enforceLicenseBeforeInstall(tenantId, mpPlugin, version, request);

        // Parse manifest from snapshot
        PluginManifestExtended manifest;
        try {
            manifest = objectMapper.readValue(version.getManifestSnapshot(), PluginManifestExtended.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse manifest snapshot: " + e.getMessage());
        }

        // Build import request
        ImportRequest importRequest = new ImportRequest();
        try {
            importRequest.setConflictStrategy(ImportRequest.ConflictStrategy.valueOf(request.getConflictStrategy()));
        } catch (IllegalArgumentException e) {
            importRequest.setConflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE);
        }
        importRequest.setAutoPublishModels(request.isAutoPublishModels());
        importRequest.setAutoPublishFields(request.isAutoPublishFields());
        importRequest.setAutoPublishCommands(request.isAutoPublishCommands());
        importRequest.setAutoPublishPages(request.isAutoPublishPages());

        // Execute import via existing PluginImportService
        ImportExecuteResult result = pluginImportService.executeFromManifest(manifest, importRequest);

        if (result.isSuccess()) {
            // Create install record
            MarketplaceInstall install = MarketplaceInstall.builder()
                    .pid(UlidGenerator.nextULID())
                    .tenantId(tenantId)
                    .marketplacePluginPid(mpPlugin.getPid())
                    .marketplaceVersionPid(version.getPid())
                    .pluginPid(result.getPluginPid())
                    .installedVersion(version.getVersion())
                    .installedAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
            installMapper.insert(install);

            // Create entitlement based on license mode
            if (entitlementChecker.isEnabled()) {
                String licenseMode = mpPlugin.getLicenseMode() != null ? mpPlugin.getLicenseMode() : "free";
                if ("free".equals(licenseMode)) {
                    entitlementProvisioner.createFreeEntitlement(tenantId, pluginId);
                } else if ("platform".equals(licenseMode)) {
                    // Auto-start trial if eligible, otherwise entitlement requires admin grant or license token
                    if (entitlementProvisioner.isEligibleForTrial(tenantId, pluginId)) {
                        entitlementProvisioner.grantTrial(tenantId, pluginId);
                        log.info("Auto-started trial for PLATFORM plugin {} tenant {}", pluginId, tenantId);
                    } else {
                        log.info("PLATFORM plugin {} installed for tenant {} — requires license activation", pluginId, tenantId);
                    }
                }
                // VENDOR mode: no auto-entitlement, requires vendor-issued license token
            }

            // Update install counts — G2 table, system tenant context
            SystemTenantContextExecutor.runAsSystem(() -> pluginMapper.incrementInstallCount(mpPlugin.getPid()));

            log.info("Marketplace plugin installed: {} v{} for tenant {}", pluginId, version.getVersion(), tenantId);
        }

        return result;
    }

    public List<MarketplacePluginDTO> getInstalled() {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) return List.of();

        List<MarketplaceInstall> installs = installMapper.findByTenant(tenantId);
        // G2 tables — system tenant context
        Map<String, MarketplaceCategory> categoryMap = new HashMap<>();
        SystemTenantContextExecutor.executeAsSystem(() -> categoryMapper.findAll())
            .forEach(c -> categoryMap.put(c.getCode(), c));

        return installs.stream().map(install -> {
            MarketplacePlugin p = SystemTenantContextExecutor.executeAsSystem(() -> pluginMapper.findByPid(install.getMarketplacePluginPid()));
            if (p == null) return null;
            MarketplaceCategory cat = categoryMap.get(p.getCategoryCode());
            return MarketplacePluginDTO.builder()
                    .pid(p.getPid())
                    .pluginId(p.getPluginId())
                    .namespace(p.getNamespace())
                    .displayName(p.getDisplayName())
                    .summary(p.getSummary())
                    .author(p.getAuthor())
                    .iconUrl(p.getIconUrl())
                    .pluginType(p.getPluginType())
                    .categoryCode(p.getCategoryCode())
                    .categoryName(cat != null ? cat.getDisplayNameEn() : null)
                    .status(p.getStatus())
                    .installCount(p.getInstallCount())
                    .latestVersion(p.getLatestVersion())
                    .licenseMode(p.getLicenseMode())
                    .publishedAt(p.getPublishedAt())
                    .installed(true)
                    .installedVersion(install.getInstalledVersion())
                    .build();
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Transactional
    public void uninstall(String pluginId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new RuntimeException("Tenant context required");
        }
        // G2 table — system tenant context
        MarketplacePlugin mpPlugin = SystemTenantContextExecutor.executeAsSystem(() -> pluginMapper.findByPluginId(pluginId));
        if (mpPlugin == null) {
            throw new RuntimeException("Plugin not found: " + pluginId);
        }
        MarketplaceInstall install = installMapper.findByTenantAndPlugin(tenantId, mpPlugin.getPid());
        if (install == null) {
            throw new RuntimeException("Plugin is not installed");
        }
        installMapper.deleteById(install.getId());
        if (entitlementChecker.isEnabled()) {
            entitlementProvisioner.disableEntitlement(tenantId, pluginId, "Plugin uninstalled");
        }
        log.info("Marketplace install record removed: {} for tenant {}", pluginId, tenantId);
    }

    /**
     * Server-to-server install: marketplace pushes plugin to customer instance.
     * InstallToken is a JWT signed by the marketplace server's key.
     */
    @Transactional
    public ImportExecuteResult serverInstall(String pluginId, String installToken) {
        // TODO: validate installToken against marketplace public key
        // For now, delegate to normal install with overwrite
        if (installToken == null || installToken.isBlank()) {
            throw new RuntimeException("installToken is required for server-to-server install");
        }
        log.info("S2S install: pluginId={}", pluginId);
        MarketplaceInstallRequest request = new MarketplaceInstallRequest();
        request.setAutoPublishPages(true);
        request.setInstallToken(installToken);
        return install(pluginId, request);
    }

    private void enforceLicenseBeforeInstall(
            Long tenantId,
            MarketplacePlugin plugin,
            MarketplaceVersion version,
            MarketplaceInstallRequest request
    ) {
        String licenseMode = StringUtils.hasText(plugin.getLicenseMode()) ? plugin.getLicenseMode() : "free";
        if ("free".equalsIgnoreCase(licenseMode)) {
            return;
        }
        if (entitlementChecker.isEnabled() && entitlementChecker.isPluginActive(tenantId, plugin.getPluginId())) {
            return;
        }
        if (!StringUtils.hasText(request.getInstallToken())) {
            throw new RuntimeException("Marketplace license or install token required before installing plugin: " + plugin.getPluginId());
        }
        marketplacePaidService.authorizeInstallTokenForInstall(
                request.getInstallToken(),
                plugin.getPid(),
                version.getPid(),
                request.getTargetInstanceUrl()
        );
    }
}
