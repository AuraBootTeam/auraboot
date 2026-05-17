package com.auraboot.framework.plugin.marketplace.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.entitlement.spi.EntitlementChecker;
import com.auraboot.framework.entitlement.spi.EntitlementProvisioner;
import com.auraboot.framework.saas.executor.SystemTenantContextExecutor;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.marketplace.dto.MarketplaceInstallRequest;
import com.auraboot.framework.plugin.marketplace.dto.MarketplacePluginDTO;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.plugin.marketplace.entity.*;
import com.auraboot.framework.plugin.marketplace.mapper.*;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.fasterxml.jackson.databind.JsonNode;
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
    private final PluginRecordMapper pluginRecordMapper;
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
        ImportRequest importRequest = buildImportRequest(request);

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

            provisionEntitlementAfterInstall(tenantId, mpPlugin);

            // Update install counts — G2 table, system tenant context
            SystemTenantContextExecutor.runAsSystem(() -> pluginMapper.incrementInstallCount(mpPlugin.getPid()));

            log.info("Marketplace plugin installed: {} v{} for tenant {}", pluginId, version.getVersion(), tenantId);
        }

        return result;
    }

    public Map<String, Object> previewUpgrade(String pluginId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new RuntimeException("Tenant context required for upgrade preview");
        }

        UpgradeTarget target = resolveUpgradeTarget(pluginId, tenantId);
        ensureUpgradeAvailable(target);

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("success", true);
        preview.put("pluginId", target.marketplacePlugin().getPluginId());
        preview.put("upgradeFromPluginId", target.sourcePluginId());
        preview.put("installedVersion", target.installedVersion());
        preview.put("latestVersion", target.version().getVersion());
        preview.put("upgradeType", target.isReplacementUpgrade() ? "replacement" : "version");
        return preview;
    }

    @Transactional
    public ImportExecuteResult upgrade(String pluginId, MarketplaceInstallRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new RuntimeException("Tenant context required for upgrade");
        }
        if (request == null) {
            request = new MarketplaceInstallRequest();
        }

        UpgradeTarget target = resolveUpgradeTarget(pluginId, tenantId);
        ensureUpgradeAvailable(target);
        enforceLicenseBeforeInstall(tenantId, target.marketplacePlugin(), target.version(), request);

        PluginManifestExtended manifest;
        try {
            manifest = objectMapper.readValue(target.version().getManifestSnapshot(), PluginManifestExtended.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse manifest snapshot: " + e.getMessage());
        }

        ImportRequest importRequest = buildImportRequest(request);
        ImportExecuteResult result = pluginImportService.executeFromManifest(manifest, importRequest);

        if (result.isSuccess()) {
            if (target.marketplaceInstall() != null) {
                installMapper.updateInstalledVersion(
                        tenantId,
                        target.marketplacePlugin().getPid(),
                        target.version().getPid(),
                        target.version().getVersion()
                );
            } else {
                MarketplaceInstall install = MarketplaceInstall.builder()
                        .pid(UlidGenerator.nextULID())
                        .tenantId(tenantId)
                        .marketplacePluginPid(target.marketplacePlugin().getPid())
                        .marketplaceVersionPid(target.version().getPid())
                        .pluginPid(result.getPluginPid())
                        .installedVersion(target.version().getVersion())
                        .installedAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();
                installMapper.insert(install);
                SystemTenantContextExecutor.runAsSystem(
                        () -> pluginMapper.incrementInstallCount(target.marketplacePlugin().getPid()));
            }

            if (target.marketplaceInstall() == null) {
                provisionEntitlementAfterInstall(tenantId, target.marketplacePlugin());
            }
            log.info("Marketplace plugin upgraded: {} from {} to {} for tenant {}",
                    pluginId, target.installedVersion(), target.version().getVersion(), tenantId);
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

    private ImportRequest buildImportRequest(MarketplaceInstallRequest request) {
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
        return importRequest;
    }

    private UpgradeTarget resolveUpgradeTarget(String pluginId, Long tenantId) {
        MarketplacePlugin mpPlugin = SystemTenantContextExecutor.executeAsSystem(
                () -> pluginMapper.findByPluginId(pluginId));
        if (mpPlugin == null) {
            throw new RuntimeException("Plugin not found in marketplace: " + pluginId);
        }
        if (!StatusConstants.PUBLISHED.equals(mpPlugin.getStatus())) {
            throw new RuntimeException("Plugin is not published: " + pluginId);
        }

        MarketplaceVersion version = SystemTenantContextExecutor.executeAsSystem(
                () -> versionMapper.findLatestPublished(mpPlugin.getPid()));
        if (version == null) {
            throw new RuntimeException("No published version available");
        }

        MarketplaceInstall marketplaceInstall = installMapper.findByTenantAndPlugin(tenantId, mpPlugin.getPid());
        if (marketplaceInstall != null) {
            return new UpgradeTarget(
                    mpPlugin,
                    version,
                    marketplaceInstall,
                    mpPlugin.getPluginId(),
                    marketplaceInstall.getInstalledVersion(),
                    false
            );
        }

        PluginRecord samePluginInstall = pluginRecordMapper.findByTenantAndPluginId(mpPlugin.getPluginId());
        if (samePluginInstall != null) {
            return new UpgradeTarget(
                    mpPlugin,
                    version,
                    null,
                    mpPlugin.getPluginId(),
                    samePluginInstall.getVersion(),
                    false
            );
        }

        Map<String, PluginRecord> directInstallsByPluginId = new HashMap<>();
        pluginRecordMapper.findByTenant()
                .forEach(record -> directInstallsByPluginId.put(record.getPluginId(), record));
        for (String sourcePluginId : getUpgradeSourcePluginIds(version, mpPlugin.getPluginId())) {
            PluginRecord sourceInstall = directInstallsByPluginId.get(sourcePluginId);
            if (sourceInstall != null) {
                return new UpgradeTarget(
                        mpPlugin,
                        version,
                        null,
                        sourcePluginId,
                        sourceInstall.getVersion(),
                        true
                );
            }
        }

        throw new RuntimeException("No installed plugin can be upgraded to: " + pluginId);
    }

    private void ensureUpgradeAvailable(UpgradeTarget target) {
        if (target.installedVersion() == null
                || compareVersions(target.version().getVersion(), target.installedVersion()) <= 0) {
            throw new RuntimeException("Plugin is already at the latest version: "
                    + target.marketplacePlugin().getPluginId());
        }
    }

    private Set<String> getUpgradeSourcePluginIds(MarketplaceVersion version, String pluginId) {
        if (version.getManifestSnapshot() == null || version.getManifestSnapshot().isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> sourcePluginIds = new LinkedHashSet<>();
        try {
            JsonNode manifest = objectMapper.readTree(version.getManifestSnapshot());
            addManifestPluginIds(sourcePluginIds, manifest.get("upgradesFrom"));
            addManifestPluginIds(sourcePluginIds, manifest.get("replaces"));
        } catch (Exception e) {
            log.warn("Failed to parse marketplace upgrade metadata for {}: {}", pluginId, e.getMessage());
        }
        sourcePluginIds.remove(pluginId);
        return sourcePluginIds;
    }

    private void addManifestPluginIds(Set<String> target, JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual() && !node.asText().isBlank()) {
            target.add(node.asText());
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                addManifestPluginIds(target, item);
            }
            return;
        }
        JsonNode pluginId = node.get("pluginId");
        if (pluginId != null && pluginId.isTextual() && !pluginId.asText().isBlank()) {
            target.add(pluginId.asText());
        }
    }

    private void provisionEntitlementAfterInstall(Long tenantId, MarketplacePlugin mpPlugin) {
        if (!entitlementChecker.isEnabled()) {
            return;
        }
        String licenseMode = mpPlugin.getLicenseMode() != null ? mpPlugin.getLicenseMode() : "free";
        String pluginId = mpPlugin.getPluginId();
        if ("free".equals(licenseMode)) {
            entitlementProvisioner.createFreeEntitlement(tenantId, pluginId);
        } else if ("platform".equals(licenseMode)) {
            if (entitlementProvisioner.isEligibleForTrial(tenantId, pluginId)) {
                entitlementProvisioner.grantTrial(tenantId, pluginId);
                log.info("Auto-started trial for PLATFORM plugin {} tenant {}", pluginId, tenantId);
            } else {
                log.info("PLATFORM plugin {} installed for tenant {} — requires license activation", pluginId, tenantId);
            }
        }
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

    private int compareVersions(String left, String right) {
        if (Objects.equals(left, right)) return 0;
        String[] leftParts = normalizeVersion(left).split("\\.");
        String[] rightParts = normalizeVersion(right).split("\\.");
        int max = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < max; i++) {
            int l = i < leftParts.length ? parseVersionPart(leftParts[i]) : 0;
            int r = i < rightParts.length ? parseVersionPart(rightParts[i]) : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    private String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return "0";
        }
        String normalized = version.trim();
        return normalized.startsWith("v") || normalized.startsWith("V")
                ? normalized.substring(1)
                : normalized;
    }

    private int parseVersionPart(String part) {
        String digits = part == null ? "" : part.replaceFirst("[^0-9].*$", "");
        if (digits.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record UpgradeTarget(
            MarketplacePlugin marketplacePlugin,
            MarketplaceVersion version,
            MarketplaceInstall marketplaceInstall,
            String sourcePluginId,
            String installedVersion,
            boolean isReplacementUpgrade
    ) {}
}
