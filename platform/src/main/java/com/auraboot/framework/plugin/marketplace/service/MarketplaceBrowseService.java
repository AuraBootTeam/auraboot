package com.auraboot.framework.plugin.marketplace.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.marketplace.dto.*;
import com.auraboot.framework.plugin.marketplace.entity.*;
import com.auraboot.framework.plugin.marketplace.mapper.*;
import com.auraboot.framework.saas.executor.SystemTenantContextExecutor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketplaceBrowseService {

    private final MarketplacePluginMapper pluginMapper;
    private final MarketplaceVersionMapper versionMapper;
    private final MarketplaceInstallMapper installMapper;
    private final MarketplaceCategoryMapper categoryMapper;
    private final PluginRecordMapper pluginRecordMapper;
    private final ObjectMapper objectMapper;

    public List<MarketplacePluginDTO> search(String keyword, String category, String sort) {
        // G2 tables (plugin catalog, categories) — read with system tenant context
        List<MarketplacePlugin> plugins = SystemTenantContextExecutor.executeAsSystem(() -> {
            if (keyword != null && !keyword.isBlank()) {
                return pluginMapper.searchPublished(keyword.trim());
            } else if (category != null && !category.isBlank()) {
                return pluginMapper.findByCategory(category);
            } else {
                return pluginMapper.findPublished();
            }
        });

        // Sort
        if ("newest".equals(sort)) {
            plugins.sort(Comparator.comparing(MarketplacePlugin::getPublishedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        } else if ("name".equals(sort)) {
            plugins.sort(Comparator.comparing(p -> p.getDisplayName() != null ? p.getDisplayName() : p.getPluginId()));
        }
        // default is "popular" — already sorted by install_count DESC

        // Get installed plugins for current tenant (marketplace installs + direct imports)
        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, MarketplaceInstall> installedMap = new HashMap<>();
        Map<String, PluginRecord> directInstalledMap = new HashMap<>();
        if (tenantId != null) {
            installMapper.findByTenant(tenantId).forEach(i -> installedMap.put(i.getMarketplacePluginPid(), i));
            // Also check ab_plugin for plugins installed via direct import (not through marketplace)
            List<PluginRecord> tenantPlugins = pluginRecordMapper.findByTenant();
            tenantPlugins.forEach(pr -> directInstalledMap.put(pr.getPluginId(), pr));
        }

        // Map category names — G2 table
        Map<String, MarketplaceCategory> categoryMap = new HashMap<>();
        SystemTenantContextExecutor.executeAsSystem(() -> categoryMapper.findAll())
            .forEach(c -> categoryMap.put(c.getCode(), c));

        final Map<String, PluginRecord> directMap = directInstalledMap;
        return plugins.stream().map(p -> {
            MarketplaceInstall mpInstall = installedMap.get(p.getPid());
            PluginRecord directInstall = directMap.get(p.getPluginId());
            return toDTO(p, mpInstall, directInstall, categoryMap.get(p.getCategoryCode()));
        }).collect(Collectors.toList());
    }

    public MarketplacePluginDetailDTO getDetail(String pluginId) {
        // G2 tables — system tenant context
        MarketplacePlugin plugin = SystemTenantContextExecutor.executeAsSystem(() -> pluginMapper.findByPluginId(pluginId));
        if (plugin == null) {
            throw new RuntimeException("Plugin not found: " + pluginId);
        }

        List<MarketplaceVersion> versions = SystemTenantContextExecutor.executeAsSystem(() -> versionMapper.findByPluginPid(plugin.getPid()));

        Long tenantId = MetaContext.getCurrentTenantId();
        MarketplaceInstall mpInstall = tenantId != null ? installMapper.findByTenantAndPlugin(tenantId, plugin.getPid()) : null;
        PluginRecord directInstall = tenantId != null ? pluginRecordMapper.findByTenantAndPluginId(plugin.getPluginId()) : null;
        boolean installed = mpInstall != null || directInstall != null;
        String installedVersion = mpInstall != null ? mpInstall.getInstalledVersion()
                : (directInstall != null ? directInstall.getVersion() : null);

        MarketplaceCategory category = plugin.getCategoryCode() != null
                ? SystemTenantContextExecutor.executeAsSystem(() -> categoryMapper.findByCode(plugin.getCategoryCode()))
                : null;

        return MarketplacePluginDetailDTO.builder()
                .pid(plugin.getPid())
                .pluginId(plugin.getPluginId())
                .namespace(plugin.getNamespace())
                .displayName(plugin.getDisplayName())
                .displayNameZh(plugin.getDisplayNameZh())
                .displayNameEn(plugin.getDisplayNameEn())
                .summary(plugin.getSummary())
                .description(plugin.getDescription())
                .author(plugin.getAuthor())
                .homepage(plugin.getHomepage())
                .iconUrl(plugin.getIconUrl())
                .pluginType(plugin.getPluginType())
                .categoryCode(plugin.getCategoryCode())
                .categoryName(category != null ? category.getDisplayNameEn() : null)
                .tags(parseTags(plugin.getTags()))
                .status(plugin.getStatus())
                .visibility(plugin.getVisibility())
                .featured(plugin.getFeatured())
                .installCount(plugin.getInstallCount())
                .latestVersion(plugin.getLatestVersion())
                .totalVersions(plugin.getTotalVersions())
                .minPlatformVersion(plugin.getMinPlatformVersion())
                .licenseMode(plugin.getLicenseMode())
                .createdAt(plugin.getCreatedAt())
                .publishedAt(plugin.getPublishedAt())
                .installed(installed)
                .installedVersion(installedVersion)
                .versions(versions.stream().map(this::toVersionDTO).collect(Collectors.toList()))
                .readmeMarkdown(plugin.getReadmeOverride() != null ? plugin.getReadmeOverride() : plugin.getReadmeMarkdown())
                .screenshots(parseScreenshots(plugin.getScreenshotsOverride() != null ? plugin.getScreenshotsOverride() : plugin.getScreenshots()))
                .averageRating(plugin.getAverageRating())
                .reviewCount(plugin.getReviewCount())
                .build();
    }

    public List<MarketplacePluginDTO> getFeatured() {
        // G2 tables — system tenant context
        List<MarketplacePlugin> plugins = SystemTenantContextExecutor.executeAsSystem(() -> pluginMapper.findFeatured());
        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, MarketplaceInstall> installedMap = new HashMap<>();
        Map<String, PluginRecord> directInstalledMap = new HashMap<>();
        if (tenantId != null) {
            installMapper.findByTenant(tenantId).forEach(i -> installedMap.put(i.getMarketplacePluginPid(), i));
            pluginRecordMapper.findByTenant().forEach(pr -> directInstalledMap.put(pr.getPluginId(), pr));
        }
        Map<String, MarketplaceCategory> categoryMap = new HashMap<>();
        SystemTenantContextExecutor.executeAsSystem(() -> categoryMapper.findAll())
            .forEach(c -> categoryMap.put(c.getCode(), c));
        final Map<String, PluginRecord> directMap = directInstalledMap;
        return plugins.stream().map(p -> toDTO(p, installedMap.get(p.getPid()), directMap.get(p.getPluginId()), categoryMap.get(p.getCategoryCode()))).collect(Collectors.toList());
    }

    public List<MarketplaceCategory> getCategories() {
        return SystemTenantContextExecutor.executeAsSystem(() -> categoryMapper.findAll());
    }

    public List<MarketplaceVersionDTO> getVersions(String pluginId) {
        return SystemTenantContextExecutor.executeAsSystem(() -> {
            MarketplacePlugin plugin = pluginMapper.findByPluginId(pluginId);
            if (plugin == null) {
                throw new RuntimeException("Plugin not found: " + pluginId);
            }
            return versionMapper.findByPluginPid(plugin.getPid()).stream()
                    .map(this::toVersionDTO).collect(Collectors.toList());
        });
    }

    private MarketplacePluginDTO toDTO(MarketplacePlugin p, MarketplaceInstall mpInstall, PluginRecord directInstall, MarketplaceCategory category) {
        boolean installed = mpInstall != null || directInstall != null;
        String installedVersion = mpInstall != null ? mpInstall.getInstalledVersion()
                : (directInstall != null ? directInstall.getVersion() : null);
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
                .categoryName(category != null ? category.getDisplayNameEn() : null)
                .tags(parseTags(p.getTags()))
                .status(p.getStatus())
                .featured(p.getFeatured())
                .installCount(p.getInstallCount())
                .latestVersion(p.getLatestVersion())
                .licenseMode(p.getLicenseMode())
                .publishedAt(p.getPublishedAt())
                .installed(installed)
                .installedVersion(installedVersion)
                .averageRating(p.getAverageRating())
                .reviewCount(p.getReviewCount())
                .build();
    }

    private MarketplaceVersionDTO toVersionDTO(MarketplaceVersion v) {
        return MarketplaceVersionDTO.builder()
                .pid(v.getPid())
                .version(v.getVersion())
                .changelog(v.getChangelog())
                .changelogZh(v.getChangelogZh())
                .dependencies(parseTags(v.getDependencies()))
                .minPlatformVersion(v.getMinPlatformVersion())
                .dslVersion(v.getDslVersion())
                .status(v.getStatus())
                .installCount(v.getInstallCount())
                .createdAt(v.getCreatedAt())
                .publishedAt(v.getPublishedAt())
                .build();
    }

    private List<String> parseTags(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseScreenshots(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
