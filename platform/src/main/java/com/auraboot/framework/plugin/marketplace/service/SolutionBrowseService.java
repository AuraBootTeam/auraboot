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
public class SolutionBrowseService {

    private final MarketplaceSolutionMapper solutionMapper;
    private final MarketplaceSolutionInstallMapper solutionInstallMapper;
    private final MarketplacePluginMapper pluginMapper;
    private final PluginRecordMapper pluginRecordMapper;
    private final ObjectMapper objectMapper;

    /**
     * Search published solutions, optionally filtered by industry or keyword.
     */
    public List<SolutionDTO> search(String keyword, String industry, String sort) {
        // G2 tables — system tenant context
        List<MarketplaceSolution> solutions = SystemTenantContextExecutor.executeAsSystem(() -> {
            if (keyword != null && !keyword.isBlank()) {
                return solutionMapper.searchPublished(keyword.trim());
            } else if (industry != null && !industry.isBlank()) {
                return solutionMapper.findByIndustry(industry);
            } else {
                return solutionMapper.findPublished();
            }
        });

        if ("newest".equals(sort)) {
            solutions.sort(Comparator.comparing(MarketplaceSolution::getPublishedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        } else if ("name".equals(sort)) {
            solutions.sort(Comparator.comparing(s -> s.getName() != null ? s.getName() : s.getCode()));
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        Set<String> installedSolutionPids = new HashSet<>();
        if (tenantId != null) {
            solutionInstallMapper.findByTenant(tenantId)
                .forEach(i -> installedSolutionPids.add(i.getSolutionPid()));
        }

        return solutions.stream().map(s -> toDTO(s, installedSolutionPids.contains(s.getPid())))
            .collect(Collectors.toList());
    }

    /**
     * Get solution detail by code with plugin breakdown.
     */
    public SolutionDetailDTO getDetail(String code) {
        // G2 table — system tenant context
        MarketplaceSolution solution = SystemTenantContextExecutor.executeAsSystem(() -> solutionMapper.findByCode(code));
        if (solution == null) {
            throw new RuntimeException("Solution not found: " + code);
        }

        List<String> pluginCodes = parseJsonList(solution.getPluginCodes());

        Long tenantId = MetaContext.getCurrentTenantId();
        boolean installed = false;
        Set<String> installedPluginIds = new HashSet<>();

        if (tenantId != null) {
            MarketplaceSolutionInstall inst = solutionInstallMapper.findByTenantAndSolution(tenantId, solution.getPid());
            installed = inst != null;

            // Check which plugins are installed
            List<PluginRecord> tenantPlugins = pluginRecordMapper.findByTenant();
            tenantPlugins.forEach(pr -> installedPluginIds.add(pr.getPluginId()));
        }

        // Resolve plugin info for each code — G2 table
        List<SolutionDetailDTO.SolutionPluginInfo> pluginInfos = pluginCodes.stream().map(pc -> {
            MarketplacePlugin mpPlugin = SystemTenantContextExecutor.executeAsSystem(() -> pluginMapper.findByPluginId(pc));
            if (mpPlugin != null) {
                return SolutionDetailDTO.SolutionPluginInfo.builder()
                    .pluginId(pc)
                    .displayName(mpPlugin.getDisplayName())
                    .summary(mpPlugin.getSummary())
                    .iconUrl(mpPlugin.getIconUrl())
                    .installed(installedPluginIds.contains(pc))
                    .availableInMarketplace(true)
                    .build();
            } else {
                return SolutionDetailDTO.SolutionPluginInfo.builder()
                    .pluginId(pc)
                    .displayName(pc)
                    .installed(installedPluginIds.contains(pc))
                    .availableInMarketplace(false)
                    .build();
            }
        }).collect(Collectors.toList());

        return SolutionDetailDTO.builder()
            .pid(solution.getPid())
            .code(solution.getCode())
            .name(solution.getName())
            .nameZh(solution.getNameZh())
            .nameEn(solution.getNameEn())
            .description(solution.getDescription())
            .descriptionZh(solution.getDescriptionZh())
            .descriptionEn(solution.getDescriptionEn())
            .industry(solution.getIndustry())
            .pluginCodes(pluginCodes)
            .plugins(pluginInfos)
            .iconUrl(solution.getIconUrl())
            .coverImageUrl(solution.getCoverImageUrl())
            .screenshots(parseJsonList(solution.getScreenshots()))
            .readmeMarkdown(solution.getReadmeMarkdown())
            .priceType(solution.getPriceType())
            .price(solution.getPrice())
            .status(solution.getStatus())
            .installCount(solution.getInstallCount())
            .averageRating(solution.getAverageRating())
            .reviewCount(solution.getReviewCount())
            .featured(solution.getFeatured())
            .tags(parseJsonList(solution.getTags()))
            .createdAt(solution.getCreatedAt())
            .publishedAt(solution.getPublishedAt())
            .installed(installed)
            .build();
    }

    /**
     * Get featured solutions.
     */
    public List<SolutionDTO> getFeatured() {
        // G2 table — system tenant context
        List<MarketplaceSolution> solutions = SystemTenantContextExecutor.executeAsSystem(() -> solutionMapper.findFeatured());
        Long tenantId = MetaContext.getCurrentTenantId();
        Set<String> installedPids = new HashSet<>();
        if (tenantId != null) {
            solutionInstallMapper.findByTenant(tenantId)
                .forEach(i -> installedPids.add(i.getSolutionPid()));
        }
        return solutions.stream().map(s -> toDTO(s, installedPids.contains(s.getPid())))
            .collect(Collectors.toList());
    }

    /**
     * Get distinct industries from published solutions.
     */
    public List<String> getIndustries() {
        // G2 table — system tenant context
        return SystemTenantContextExecutor.executeAsSystem(() -> solutionMapper.findPublished()).stream()
            .map(MarketplaceSolution::getIndustry)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }

    /**
     * Get installed solutions for current tenant.
     */
    public List<SolutionDTO> getInstalled() {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) return List.of();

        List<MarketplaceSolutionInstall> installs = solutionInstallMapper.findByTenant(tenantId);
        return installs.stream().map(inst -> {
            // G2 table — system tenant context
            MarketplaceSolution s = SystemTenantContextExecutor.executeAsSystem(() -> solutionMapper.findByPid(inst.getSolutionPid()));
            if (s == null) return null;
            return toDTO(s, true);
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private SolutionDTO toDTO(MarketplaceSolution s, boolean installed) {
        List<String> pluginCodes = parseJsonList(s.getPluginCodes());
        return SolutionDTO.builder()
            .pid(s.getPid())
            .code(s.getCode())
            .name(s.getName())
            .nameZh(s.getNameZh())
            .nameEn(s.getNameEn())
            .description(s.getDescription())
            .industry(s.getIndustry())
            .pluginCodes(pluginCodes)
            .iconUrl(s.getIconUrl())
            .coverImageUrl(s.getCoverImageUrl())
            .priceType(s.getPriceType())
            .price(s.getPrice())
            .status(s.getStatus())
            .installCount(s.getInstallCount())
            .averageRating(s.getAverageRating())
            .reviewCount(s.getReviewCount())
            .featured(s.getFeatured())
            .tags(parseJsonList(s.getTags()))
            .publishedAt(s.getPublishedAt())
            .installed(installed)
            .pluginCount(pluginCodes.size())
            .build();
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
