package com.auraboot.framework.plugin.marketplace.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.saas.executor.SystemTenantContextExecutor;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.marketplace.dto.SolutionInstallResult;
import com.auraboot.framework.plugin.marketplace.entity.*;
import com.auraboot.framework.plugin.marketplace.mapper.*;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import com.auraboot.framework.common.constant.StatusConstants;

@Slf4j
@Service
@RequiredArgsConstructor
public class SolutionInstallService {

    private final MarketplaceSolutionMapper solutionMapper;
    private final MarketplaceSolutionInstallMapper solutionInstallMapper;
    private final MarketplacePluginMapper pluginMapper;
    private final MarketplaceInstallMapper marketplaceInstallMapper;
    private final PluginImportService pluginImportService;
    private final ObjectMapper objectMapper;

    @Value("${auraboot.plugins.base-dir:plugins}")
    private String pluginsBaseDir;

    /**
     * Install a solution by sequentially installing all its plugins.
     */
    @Transactional
    public SolutionInstallResult install(String solutionCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new RuntimeException("Tenant context required for solution installation");
        }

        // G2 table — system tenant context
        MarketplaceSolution solution = SystemTenantContextExecutor.executeAsSystem(() -> solutionMapper.findByCode(solutionCode));
        if (solution == null) {
            throw new RuntimeException("Solution not found: " + solutionCode);
        }
        if (!StatusConstants.PUBLISHED.equals(solution.getStatus())) {
            throw new RuntimeException("Solution is not published: " + solutionCode);
        }

        // Check if already installed
        MarketplaceSolutionInstall existing = solutionInstallMapper.findByTenantAndSolution(tenantId, solution.getPid());
        if (existing != null) {
            throw new RuntimeException("Solution already installed: " + solutionCode);
        }

        List<String> pluginCodes = parseJsonList(solution.getPluginCodes());
        List<SolutionInstallResult.PluginInstallStatus> pluginResults = new ArrayList<>();
        List<String> installedPluginPids = new ArrayList<>();
        int installed = 0;
        int skipped = 0;
        int failed = 0;

        for (String pluginCode : pluginCodes) {
            try {
                // Check if plugin is already installed for this tenant — G2 table
                MarketplacePlugin mpPlugin = SystemTenantContextExecutor.executeAsSystem(() -> pluginMapper.findByPluginId(pluginCode));
                if (mpPlugin != null) {
                    MarketplaceInstall mpInstall = marketplaceInstallMapper.findByTenantAndPlugin(tenantId, mpPlugin.getPid());
                    if (mpInstall != null) {
                        pluginResults.add(SolutionInstallResult.PluginInstallStatus.builder()
                            .pluginCode(pluginCode)
                            .status(StatusConstants.SKIPPED)
                            .message("Already installed via marketplace")
                            .build());
                        skipped++;
                        continue;
                    }
                }

                // Try to install from local plugin directory
                String pluginDir = pluginsBaseDir + "/" + pluginCode;
                ImportPreviewResult preview = pluginImportService.parseDirectory(pluginDir);
                if (preview == null || !preview.isValid()) {
                    String error = preview != null && preview.getErrors() != null && !preview.getErrors().isEmpty()
                        ? preview.getErrors().get(0) : "Plugin directory not found or invalid";
                    pluginResults.add(SolutionInstallResult.PluginInstallStatus.builder()
                        .pluginCode(pluginCode)
                        .status(StatusConstants.FAILED)
                        .message(error)
                        .build());
                    failed++;
                    continue;
                }

                ImportRequest importRequest = new ImportRequest();
                importRequest.setConflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE);
                importRequest.setAutoPublishModels(true);
                importRequest.setAutoPublishFields(true);
                importRequest.setAutoPublishCommands(true);
                importRequest.setAutoPublishPages(true);

                ImportExecuteResult result = pluginImportService.execute(preview.getImportId(), importRequest);
                if (result.isSuccess()) {
                    installed++;
                    if (result.getPluginPid() != null) {
                        installedPluginPids.add(result.getPluginPid());
                    }
                    pluginResults.add(SolutionInstallResult.PluginInstallStatus.builder()
                        .pluginCode(pluginCode)
                        .status(StatusConstants.INSTALLED)
                        .message("Successfully installed")
                        .build());
                } else {
                    failed++;
                    pluginResults.add(SolutionInstallResult.PluginInstallStatus.builder()
                        .pluginCode(pluginCode)
                        .status(StatusConstants.FAILED)
                        .message(result.getErrorMessage() != null ? result.getErrorMessage() : "Installation failed")
                        .build());
                }
            } catch (Exception e) {
                failed++;
                pluginResults.add(SolutionInstallResult.PluginInstallStatus.builder()
                    .pluginCode(pluginCode)
                    .status(StatusConstants.FAILED)
                    .message(e.getMessage())
                    .build());
                log.error("Failed to install plugin {} for solution {}: {}", pluginCode, solutionCode, e.getMessage(), e);
            }
        }

        // Create solution install record (even with partial failures, so user can track)
        if (installed > 0 || skipped > 0) {
            try {
                String pidsJson = objectMapper.writeValueAsString(installedPluginPids);
                MarketplaceSolutionInstall installRecord = MarketplaceSolutionInstall.builder()
                    .pid(UlidGenerator.nextULID())
                    .tenantId(tenantId)
                    .solutionPid(solution.getPid())
                    .installedPluginPids(pidsJson)
                    .installedAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
                solutionInstallMapper.insert(installRecord);
                // G2 table — system tenant context
                SystemTenantContextExecutor.runAsSystem(() -> solutionMapper.incrementInstallCount(solution.getPid()));
            } catch (Exception e) {
                log.error("Failed to create solution install record: {}", e.getMessage(), e);
            }
        }

        boolean success = failed == 0;
        log.info("Solution install complete: {} — installed={}, skipped={}, failed={}", solutionCode, installed, skipped, failed);

        return SolutionInstallResult.builder()
            .success(success)
            .solutionCode(solutionCode)
            .totalPlugins(pluginCodes.size())
            .installedPlugins(installed)
            .skippedPlugins(skipped)
            .failedPlugins(failed)
            .pluginResults(pluginResults)
            .build();
    }

    /**
     * Uninstall a solution (removes install record, does NOT uninstall individual plugins).
     */
    @Transactional
    public void uninstall(String solutionCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new RuntimeException("Tenant context required");
        }
        // G2 table — system tenant context
        MarketplaceSolution solution = SystemTenantContextExecutor.executeAsSystem(() -> solutionMapper.findByCode(solutionCode));
        if (solution == null) {
            throw new RuntimeException("Solution not found: " + solutionCode);
        }
        MarketplaceSolutionInstall inst = solutionInstallMapper.findByTenantAndSolution(tenantId, solution.getPid());
        if (inst == null) {
            throw new RuntimeException("Solution is not installed: " + solutionCode);
        }
        solutionInstallMapper.deleteByTenantAndSolution(tenantId, solution.getPid());
        SystemTenantContextExecutor.runAsSystem(() -> solutionMapper.decrementInstallCount(solution.getPid()));
        log.info("Solution uninstalled: {} for tenant {}", solutionCode, tenantId);
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
