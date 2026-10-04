package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.common.util.LogSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Creates idempotent default CRUD page stubs for a published model. */
final class ModelDefaultPageSupport {
    private ModelDefaultPageSupport() {}

    static void create(Model model, PageSchemaMapper pageSchemaMapper, ObjectMapper objectMapper,
                       Function<Long, Long> defaultEnvironment, Logger log) {
        String modelCode = model.getCode();
        Long tenantId = model.getTenantId();
        Instant now = Instant.now();

        record PageSpec(String kind, String pageKey, String blocks) {}

        if (model.isSkipDefaultPages()) {
            log.info("Skipping all default page schemas for model: {}", LogSanitizer.safe(modelCode));
            return;
        }

        List<PageSpec> specs = new ArrayList<>();
        if (!model.isSkipListPageCreation()) {
            specs.add(new PageSpec("list", modelCode + "_list",
                "[{\"blockType\":\"toolbar\"},{\"blockType\":\"filters\"},{\"blockType\":\"table\"}]"));
        }
        if (!model.isSkipFormPageCreation()) {
            specs.add(new PageSpec("form", modelCode + "_form",
                "[{\"blockType\":\"form-section\"}]"));
        }
        if (!model.isSkipDetailPageCreation()) {
            specs.add(new PageSpec("detail", modelCode + "_detail",
                "[{\"blockType\":\"form-section\"},{\"blockType\":\"tabs\"}]"));
        }

        for (PageSpec spec : specs) {
            // Check existence by page_key (unique per tenant+namespace)
            com.auraboot.framework.meta.entity.PageSchema existing =
                pageSchemaMapper.selectAnyByPageKey(spec.pageKey());
            if (existing != null) {
                log.debug("Page schema already exists, skipping auto-create: pageKey={}", LogSanitizer.safe(spec.pageKey()));
                continue;
            }

            String modelName = StringUtils.hasText(model.getDisplayName()) ? model.getDisplayName() : modelCode;
            String kindLabel = switch (spec.kind()) {
                case "list" -> "列表";
                case "form" -> "表单";
                case "detail" -> "详情";
                default -> throw new IllegalArgumentException("Unsupported default page kind: " + spec.kind());
            };
            String pageName = modelName + kindLabel;
            String titleJson;
            try {
                titleJson = objectMapper.writeValueAsString(Map.of(
                        "zh-CN", pageName, "en", modelName + " " + spec.kind()));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                // Invalid serialization aborts publication; preserve the original cause.
                throw new IllegalStateException("Failed to serialize default page title", e);
            }

            // Resolve env_id from MetaContext or fall back to tenant default
            Long envId = com.auraboot.framework.application.tenant.MetaContext.getCurrentEnvironmentId();
            if (envId == null) {
                envId = defaultEnvironment.apply(tenantId);
            }

            int inserted = pageSchemaMapper.insertForPluginImport(
                UniqueIdGenerator.generate(),   // pid
                tenantId,                        // tenantId
                envId,                           // envId (env-layering #16)
                "published",                     // status
                spec.pageKey(),                  // pageKey
                modelCode,                       // modelCode
                pageName,                        // localized business name
                titleJson,                       // title
                null,                            // description
                spec.kind(),                     // kind
                "admin",                         // profile
                "{\"type\":\"stack\"}",          // layout
                spec.blocks(),                   // blocks
                com.auraboot.framework.meta.constant.DslRegistry.PAGE_SCHEMA_CURRENT_VERSION, // schemaVersion (v4 flat blocks + grid)
                false,                           // isTemplate
                null,                            // templateCategory
                now,                             // publishedAt
                0,                               // sortWeight
                // Mark this row as an auto-generated stub so a subsequent
                // plugin import (importPage) can overwrite it unconditionally,
                // independent of the OVERWRITE_SAFE user-modified guard.
                "{\"auto_created\":true}",      // extension
                null                             // pluginPid
            );

            if (inserted > 0) {
                log.info("Auto-created default page schema: pageKey={}, kind={}, modelCode={}",
                    LogSanitizer.safe(spec.pageKey()), LogSanitizer.safe(spec.kind()), LogSanitizer.safe(modelCode));
            }
        }
    }

}
