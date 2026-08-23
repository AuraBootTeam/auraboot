package com.auraboot.framework.meta.contribution;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.contribution.mapper.PageSchemaContributionMapper;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.auraboot.framework.plugin.dto.imports.PageContributionDefinitionDTO;
import com.auraboot.framework.plugin.exception.PluginException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates and atomically replaces one plugin's page contributions in the current environment. */
@Service
@RequiredArgsConstructor
public class PageSchemaContributionImportService {

    private final PageSchemaContributionMapper mapper;
    private final PageSchemaService pageSchemaService;
    private final PageSchemaContributionComposer composer;
    private final EnvironmentService environmentService;

    @Transactional
    public void replaceForPlugin(String pluginPid, String pluginVersion, Long tenantId,
                                 List<PageContributionDefinitionDTO> definitions) {
        List<PageContributionDefinitionDTO> safeDefinitions =
                definitions == null ? List.of() : List.copyOf(definitions);
        validateAll(pluginPid, safeDefinitions);

        Long envId = MetaContext.getCurrentEnvironmentId();
        if (envId == null) {
            envId = environmentService.findOrCreateDefaultId(tenantId);
        }
        List<PersistedPageSchemaContribution> existing = mapper.selectList(
                new LambdaQueryWrapper<PersistedPageSchemaContribution>()
                        .eq(PersistedPageSchemaContribution::getTenantId, tenantId)
                        .eq(PersistedPageSchemaContribution::getEnvId, envId)
                        .eq(PersistedPageSchemaContribution::getPluginPid, pluginPid));
        Map<String, PersistedPageSchemaContribution> byContributionId = existing.stream()
                .collect(Collectors.toMap(PersistedPageSchemaContribution::getContributionId, row -> row));
        Instant now = Instant.now();
        existing.forEach(row -> {
            row.setActive(false);
            row.setUpdatedAt(now);
            mapper.updateById(row);
        });

        for (PageContributionDefinitionDTO definition : safeDefinitions) {
            PersistedPageSchemaContribution row = byContributionId.get(definition.getId());
            if (row == null) {
                row = new PersistedPageSchemaContribution();
                row.setPid(UlidGenerator.generate());
                row.setTenantId(tenantId);
                row.setEnvId(envId);
                row.setPluginPid(pluginPid);
                row.setContributionId(definition.getId());
                row.setCreatedAt(now);
            }
            row.setPluginVersion(pluginVersion);
            row.setTargetPageKey(definition.getTargetPageKey());
            row.setSlotId(definition.getSlotId());
            row.setKind(definition.getKind().toLowerCase(Locale.ROOT));
            row.setPriority(definition.getPriority() == null ? 0 : definition.getPriority());
            row.setPayload(definition.getPayload());
            row.setActive(true);
            row.setDeletedFlag(false);
            row.setUpdatedAt(now);
            if (row.getId() == null) {
                mapper.insert(row);
            } else {
                mapper.updateById(row);
            }
        }
    }

    private void validateAll(String pluginPid, List<PageContributionDefinitionDTO> definitions) {
        Set<String> ids = new HashSet<>();
        for (PageContributionDefinitionDTO definition : definitions) {
            if (definition == null || !definition.isValid()) {
                throw new PluginException("Invalid page contribution in plugin " + pluginPid);
            }
            if (!ids.add(definition.getId())) {
                throw new PluginException("Duplicate page contribution id: " + definition.getId());
            }
            try {
                PageSchemaContributionKind.valueOf(definition.getKind().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new PluginException("Unsupported page contribution kind: " + definition.getKind());
            }
        }

        definitions.stream().collect(Collectors.groupingBy(PageContributionDefinitionDTO::getTargetPageKey))
                .forEach((pageKey, pageDefinitions) -> validateTargetPage(pageKey, pluginPid, pageDefinitions));
    }

    private void validateTargetPage(String pageKey, String pluginPid,
                                    List<PageContributionDefinitionDTO> definitions) {
        PageSchemaDTO target = pageSchemaService.findAnyByPageKey(pageKey);
        if (target == null) {
            throw new PluginException("Dangling page contribution target: " + pageKey);
        }
        List<PageSchemaContribution> runtimeContributions = definitions.stream()
                .map(definition -> new PageSchemaContribution(
                        definition.getId(), pluginPid, definition.getSlotId(),
                        PageSchemaContributionKind.valueOf(definition.getKind().toUpperCase(Locale.ROOT)),
                        definition.getPriority() == null ? 0 : definition.getPriority(),
                        definition.getPayload()))
                .toList();
        try {
            composer.compose(target, runtimeContributions);
        } catch (RuntimeException exception) {
            throw new PluginException("Invalid page contribution target contract for " + pageKey
                    + ": " + exception.getMessage(), exception);
        }
    }
}
