package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.dto.imports.ResourceAction;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.dto.imports.SavedViewDefinitionDTO;
import com.auraboot.framework.view.entity.SavedView;
import com.auraboot.framework.view.entity.ViewConfig;
import com.auraboot.framework.view.mapper.SavedViewMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Owns plugin preset identity, metadata precedence and saved-view import. */
@Slf4j
@RequiredArgsConstructor
final class PluginSavedViewImporter {
    private final SavedViewMapper savedViewMapper;
    private final PageSchemaMapper pageSchemaMapper;
    private final ObjectMapper objectMapper;

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    void importSavedViews(PluginManifestExtended manifest, ImportExecuteResult result, Long tenantId) {
        if (manifest.getSavedViews() == null || manifest.getSavedViews().isEmpty()) return;

        for (SavedViewDefinitionDTO dto : manifest.getSavedViews()) {
            if (!dto.isValid()) {
                log.warn("Skipping invalid saved view: {}", logSafe(dto.getName()));
                continue;
            }

            String scope = dto.getScope() != null ? dto.getScope() : "global";
            String pageKey = dto.getPageKey();

            // Validate pageKey references an existing page in ab_page_schema.
            // A SavedView with a dangling pageKey will be permanently invisible to users
            // because useSavedViews does a strict-equals match on pageKey.
            if (pageKey != null && !pageKey.isBlank()) {
                PageSchema page = pageSchemaMapper.selectAnyByPageKey(pageKey);
                if (page == null) {
                    String msg = "[S-SAVED-VIEW] pageKey '" + logSafe(pageKey) + "' does not exist in ab_page_schema; "
                            + "define it as config/pages/" + logSafe(pageKey) + ".json in your plugin before declaring a SavedView";
                    log.warn("Skipping saved view '{}': {}", logSafe(dto.getName()), msg);
                    result.addWarning(msg);
                    continue;
                }
            }

            // Check if a plugin preset already exists. Stable viewKey takes precedence
            // so plugin upgrades can rename presets without creating duplicates.
            List<SavedView> existing = savedViewMapper.findGlobalViews(dto.getModelCode(), pageKey);
            SavedView existingView = existing.stream()
                    .filter(v -> matchesPluginSavedView(dto, v))
                    .findFirst()
                    .orElse(null);

            if (existingView != null) {
                // Update existing view config
                ViewConfig viewConfig = buildPluginSavedViewConfig(dto);
                existingView.setName(dto.getName());
                existingView.setViewConfig(viewConfig);
                existingView.setDescription(dto.getDescription());
                existingView.setViewType(dto.getViewType());
                existingView.setUpdatedAt(Instant.now());
                if (dto.getIsDefault() != null) existingView.setIsDefault(dto.getIsDefault());
                if (dto.getSortOrder() != null) existingView.setSortOrder(dto.getSortOrder());
                savedViewMapper.updateSavedView(existingView);
                result.incrementResourceCount(ResourceType.SAVED_VIEW, ResourceAction.UPDATE);
                log.info("Updated saved view: {} ({})", logSafe(dto.getName()), logSafe(dto.getViewType()));
            } else {
                // Create new saved view
                SavedView savedView = new SavedView();
                savedView.setPid(UlidGenerator.generate());
                savedView.setTenantId(tenantId);
                savedView.setName(dto.getName());
                savedView.setDescription(dto.getDescription());
                savedView.setModelCode(dto.getModelCode());
                savedView.setPageKey(pageKey);
                savedView.setScope(scope);
                savedView.setViewType(dto.getViewType());
                ViewConfig viewConfig = buildPluginSavedViewConfig(dto);
                savedView.setViewConfig(viewConfig);
                savedView.setIsDefault(dto.getIsDefault() != null ? dto.getIsDefault() : false);
                savedView.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
                savedView.setDeletedFlag(false);
                savedView.setCreatedAt(Instant.now());
                savedView.setUpdatedAt(Instant.now());
                savedViewMapper.insertSavedView(savedView);
                result.incrementResourceCount(ResourceType.SAVED_VIEW, ResourceAction.CREATE);
                log.info("Created saved view: {} ({})", logSafe(dto.getName()), logSafe(dto.getViewType()));
            }
        }
    }

    boolean matchesPluginSavedView(SavedViewDefinitionDTO dto, SavedView savedView) {
        if (dto == null || savedView == null) {
            return false;
        }

        String incomingKey = resolvePluginSavedViewKey(dto);
        String existingKey = getSavedViewMetaKey(savedView);
        if (!isBlank(incomingKey) && !isBlank(existingKey)) {
            return Objects.equals(incomingKey, existingKey);
        }

        return Objects.equals(savedView.getName(), dto.getName())
                && Objects.equals(savedView.getViewType(), dto.getViewType());
    }

    private ViewConfig buildPluginSavedViewConfig(SavedViewDefinitionDTO dto) {
        Map<String, Object> rawConfig = dto.getViewConfig() != null ? dto.getViewConfig() : Collections.emptyMap();
        ViewConfig viewConfig = objectMapper.convertValue(rawConfig, ViewConfig.class);
        if (viewConfig == null) {
            viewConfig = new ViewConfig();
        }

        ViewConfig.Meta incomingMeta = viewConfig.getMeta();
        viewConfig.setMeta(ViewConfig.Meta.builder()
                .viewKey(resolvePluginSavedViewKey(dto))
                .managedBy(firstNonBlank(dto.getManagedBy(), incomingMeta != null ? incomingMeta.getManagedBy() : null, "plugin"))
                .locked(firstNonNull(dto.getLocked(), incomingMeta != null ? incomingMeta.getLocked() : null, true))
                .allowUserCopy(firstNonNull(dto.getAllowUserCopy(), incomingMeta != null ? incomingMeta.getAllowUserCopy() : null, true))
                .allowUserOverride(firstNonNull(dto.getAllowUserOverride(), incomingMeta != null ? incomingMeta.getAllowUserOverride() : null, true))
                .originViewPid(incomingMeta != null ? incomingMeta.getOriginViewPid() : null)
                .capabilityStatus(incomingMeta != null ? incomingMeta.getCapabilityStatus() : null)
                .pinnedAsQuickFilter(firstNonNull(dto.getPinAsQuickFilter(),
                        incomingMeta != null ? incomingMeta.getPinnedAsQuickFilter() : null))
                .quickFilterIcon(firstNonBlank(dto.getQuickFilterIcon(),
                        incomingMeta != null ? incomingMeta.getQuickFilterIcon() : null))
                .quickFilterOrder(firstNonNull(dto.getQuickFilterOrder(),
                        incomingMeta != null ? incomingMeta.getQuickFilterOrder() : null))
                .build());
        return viewConfig;
    }

    private String resolvePluginSavedViewKey(SavedViewDefinitionDTO dto) {
        if (dto == null) {
            return null;
        }
        if (!isBlank(dto.getViewKey())) {
            return dto.getViewKey().trim();
        }
        return dto.getUniqueKey();
    }

    private String getSavedViewMetaKey(SavedView savedView) {
        if (savedView == null || savedView.getViewConfig() == null || savedView.getViewConfig().getMeta() == null) {
            return null;
        }
        return savedView.getViewConfig().getMeta().getViewKey();
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

}
