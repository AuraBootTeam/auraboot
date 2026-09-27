package com.auraboot.framework.application.release;

import com.auraboot.framework.meta.constant.DslRegistry;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.dto.PageSchemaRuntimeDTO;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Selects an immutable Application Release page for active tenant bindings. */
@Service
public final class ApplicationPageDefinitionReadService {
    public enum Source { LEGACY, APPLICATION_RELEASE }

    public record Resolution(Source source, PageSchemaDTO page) {
        public boolean isReleasePrimary() {
            return source == Source.APPLICATION_RELEASE;
        }
    }

    private final ApplicationDefinitionMapper definitions;
    private final ApplicationDefinitionResolver resolver;
    private final ObjectMapper mapper;

    public ApplicationPageDefinitionReadService(ApplicationDefinitionMapper definitions,
                                                ApplicationDefinitionResolver resolver,
                                                ObjectMapper mapper) {
        this.definitions = definitions;
        this.resolver = resolver;
        this.mapper = mapper;
    }

    public Resolution resolve(long tenantId, String applicationCode, String pageKey, PageSchema legacyPage) {
        ApplicationDefinitionMapper.ReleaseRow observed = definitions.findBoundRelease(tenantId, applicationCode);
        if (observed == null || "shadow".equals(observed.status)) {
            return legacy();
        }
        if (!"active".equals(observed.status)) {
            throw unavailable("Unsupported tenant application binding status");
        }

        ApplicationDefinitionResolver.UniqueDefinitionLookup lookup = resolver.lookupUnique(
                tenantId, applicationCode, ApplicationDefinitionResolver.ResourceType.PAGE, pageKey, null);
        if (!"active".equals(lookup.release().release().bindingStatus())) {
            return legacy();
        }
        if (lookup.definition() != null) {
            return new Resolution(Source.APPLICATION_RELEASE, toRuntimePage(lookup.definition()));
        }

        requireLegacyPageOutsideRelease(tenantId, pageKey, legacyPage, lookup);
        return legacy();
    }

    private void requireLegacyPageOutsideRelease(
            long tenantId,
            String pageKey,
            PageSchema legacyPage,
            ApplicationDefinitionResolver.UniqueDefinitionLookup lookup) {
        if (legacyPage == null || legacyPage.getPluginPid() == null || legacyPage.getPluginPid().isBlank()) {
            return;
        }
        PluginRecord plugin = definitions.findTenantPluginByPid(tenantId, legacyPage.getPluginPid());
        if (plugin == null || plugin.getPluginId() == null || plugin.getPluginId().isBlank()) {
            throw unavailable("Legacy page plugin ownership cannot be resolved: " + pageKey);
        }
        if (lookup.containsPlugin(plugin.getPluginId())) {
            throw unavailable("Active Application Release is missing owned page: " + pageKey);
        }
    }

    private PageSchemaDTO toRuntimePage(ApplicationDefinitionResolver.ResolvedDefinition resolved) {
        try {
            var source = mapper.treeToValue(resolved.definition(),
                    com.auraboot.framework.plugin.dto.imports.PageSchemaDTO.class);
            PageSchemaDTO page = new PageSchemaDTO();
            page.setPageKey(source.getPageKey());
            page.setModelCode(source.getModelCode());
            page.setKind(defaultValue(source.getKind(), "list"));
            page.setProfile(defaultValue(source.getProfile(), "admin"));
            page.setName(source.getEffectiveName());
            page.setTitle(source.getTitle());
            page.setDescription(source.getDescription());
            page.setLayout(source.getLayout());
            page.setDataSources(source.getDataSources());
            page.setRecordSource(source.getRecordSource());
            page.setBlocks(source.getBlocks() == null ? new ArrayList<>() : source.getBlocks());
            page.setSchemaVersion(source.getSchemaVersion() == null
                    ? DslRegistry.PAGE_SCHEMA_CURRENT_VERSION : source.getSchemaVersion());
            page.setMetaInfo(source.getMetaInfo());
            page.setIsTemplate(Boolean.TRUE.equals(source.getIsTemplate()));
            page.setTemplateCategory(source.getTemplateCategory());
            page.setSortWeight(source.getSortWeight() == null ? 0 : source.getSortWeight());
            page.setExtension(extension(source));
            page.setMobileUx(source.getMobileUx());
            page.setRuntime(runtime(resolved));
            return page;
        } catch (JsonProcessingException failure) {
            throw new DataRetrievalFailureException("Application Release page cannot be materialized", failure);
        }
    }

    private Map<String, Object> extension(com.auraboot.framework.plugin.dto.imports.PageSchemaDTO source) {
        Map<String, Object> extension = new LinkedHashMap<>();
        if (source.getExtension() != null) extension.putAll(source.getExtension());
        if (source.getDataSources() != null && !source.getDataSources().isEmpty()) {
            extension.put("dataSources", source.getDataSources());
        }
        if (source.getMobileUx() != null && !source.getMobileUx().isEmpty()) {
            extension.put("mobileUx", source.getMobileUx());
        }
        return extension;
    }

    private PageSchemaRuntimeDTO runtime(ApplicationDefinitionResolver.ResolvedDefinition resolved) {
        var release = resolved.release();
        String definitionDigest = definitionDigest(resolved);
        return new PageSchemaRuntimeDTO(
                "APPLICATION_RELEASE",
                release.releaseId(),
                release.bindingVersion(),
                1,
                definitionDigest,
                "application-release:" + release.releaseId()
                        + ":" + release.bindingVersion()
                        + ":" + resolved.componentDigest()
                        + ":page:" + resolved.code());
    }

    private String definitionDigest(ApplicationDefinitionResolver.ResolvedDefinition resolved) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsBytes(resolved.definition())));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new DataRetrievalFailureException("Application Release page digest cannot be computed", failure);
        }
    }

    private static Resolution legacy() {
        return new Resolution(Source.LEGACY, null);
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static DataRetrievalFailureException unavailable(String message) {
        return new DataRetrievalFailureException(message);
    }
}
