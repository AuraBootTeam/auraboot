package com.auraboot.framework.application.release;

import com.auraboot.framework.meta.constant.DslRegistry;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compares one live tenant page baseline with the same page in its immutable bound release.
 * Shadow reads are observational: callers keep returning the legacy page until an explicit
 * primary-read cutover is implemented.
 */
@Slf4j
@Service
public final class PageDefinitionShadowReadService {
    public enum Verdict { SKIPPED, EXACT_MATCH, DRIFTED, MISSING, ERROR }

    public record Result(Verdict verdict, long tenantId, String applicationCode, String releaseId,
                         long bindingVersion, String pageKey, String expectedDigest,
                         String actualDigest, String detail) {}

    private final ApplicationDefinitionMapper definitions;
    private final ApplicationDefinitionResolver resolver;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;

    public PageDefinitionShadowReadService(ApplicationDefinitionMapper definitions,
                                           ApplicationDefinitionResolver resolver,
                                           ObjectMapper mapper,
                                           MeterRegistry metrics) {
        this.definitions = definitions;
        this.resolver = resolver;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    public Result compare(long tenantId, String applicationCode, String pageKey, PageSchemaDTO legacyPage) {
        if (tenantId <= 0 || applicationCode == null || applicationCode.isBlank()
                || pageKey == null || pageKey.isBlank()) {
            return record(new Result(Verdict.SKIPPED, tenantId, applicationCode, null, 0,
                    pageKey, null, null, "shadow-read-context-missing"));
        }
        try {
            ApplicationDefinitionMapper.ReleaseRow bound = definitions.findBoundRelease(tenantId, applicationCode);
            if (bound == null || !"shadow".equals(bound.status)) {
                return record(new Result(Verdict.SKIPPED, tenantId, applicationCode,
                        bound == null ? null : bound.releaseId, bound == null ? 0 : bound.bindingVersion,
                        pageKey, null, null, bound == null ? "binding-missing" : "binding-not-shadow"));
            }

            ApplicationDefinitionResolver.ResolvedDefinition shared = resolver.findUnique(
                    tenantId, applicationCode, ApplicationDefinitionResolver.ResourceType.PAGE,
                    pageKey, null);
            if (shared == null) {
                return record(new Result(Verdict.SKIPPED, tenantId, applicationCode, bound.releaseId,
                        bound.bindingVersion, pageKey, null, null, "page-not-owned-by-application"));
            }
            if (!"shadow".equals(shared.release().bindingStatus())) {
                return record(new Result(Verdict.SKIPPED, tenantId, applicationCode,
                        shared.release().releaseId(), shared.release().bindingVersion(), pageKey,
                        null, null, "binding-changed-during-shadow-read"));
            }

            var sharedPage = mapper.treeToValue(shared.definition(),
                    com.auraboot.framework.plugin.dto.imports.PageSchemaDTO.class);
            String expected = digest(sharedProjection(sharedPage));
            String actual = legacyPage == null ? null : digest(legacyProjection(legacyPage));
            Verdict verdict = legacyPage == null ? Verdict.MISSING
                    : expected.equals(actual) ? Verdict.EXACT_MATCH : Verdict.DRIFTED;
            return record(new Result(verdict, tenantId, applicationCode,
                    shared.release().releaseId(), shared.release().bindingVersion(), pageKey,
                    expected, actual, verdict == Verdict.MISSING ? "legacy-page-missing" : null));
        } catch (Exception failure) {
            return record(new Result(Verdict.ERROR, tenantId, applicationCode, null, 0, pageKey,
                    null, null, failure.getClass().getSimpleName() + ":" + safeMessage(failure)));
        }
    }

    private Result record(Result result) {
        metrics.counter("auraboot.application.definition.shadow.read.total",
                "resource_type", "page", "verdict", result.verdict().name().toLowerCase()).increment();
        if (result.verdict() == Verdict.DRIFTED || result.verdict() == Verdict.MISSING
                || result.verdict() == Verdict.ERROR) {
            log.warn("Application page shadow read: tenantId={}, applicationCode={}, releaseId={}, bindingVersion={}, pageKey={}, verdict={}, expectedDigest={}, actualDigest={}, detail={}",
                    result.tenantId(), result.applicationCode(), result.releaseId(), result.bindingVersion(),
                    result.pageKey(), result.verdict(), result.expectedDigest(), result.actualDigest(), result.detail());
        } else if (result.verdict() == Verdict.EXACT_MATCH) {
            log.debug("Application page shadow read exact match: tenantId={}, applicationCode={}, releaseId={}, bindingVersion={}, pageKey={}",
                    result.tenantId(), result.applicationCode(), result.releaseId(), result.bindingVersion(), result.pageKey());
        }
        return result;
    }

    private JsonNode sharedProjection(com.auraboot.framework.plugin.dto.imports.PageSchemaDTO page) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("pageKey", page.getPageKey());
        value.put("modelCode", page.getModelCode());
        value.put("kind", defaultValue(page.getKind(), "list"));
        value.put("profile", defaultValue(page.getProfile(), "admin"));
        value.put("name", page.getEffectiveName());
        value.put("title", page.getTitle());
        value.put("description", page.getDescription());
        value.put("layout", page.getLayout());
        value.put("dataSources", page.getDataSources());
        value.put("recordSource", page.getRecordSource());
        value.put("blocks", page.getBlocks() == null ? new ArrayList<>() : page.getBlocks());
        value.put("schemaVersion", page.getSchemaVersion() == null
                ? DslRegistry.PAGE_SCHEMA_CURRENT_VERSION : page.getSchemaVersion());
        value.put("metaInfo", page.getMetaInfo());
        value.put("isTemplate", Boolean.TRUE.equals(page.getIsTemplate()));
        value.put("templateCategory", page.getTemplateCategory());
        value.put("sortWeight", page.getSortWeight() == null ? 0 : page.getSortWeight());
        value.put("extension", extension(page.getExtension(), page.getDataSources(), page.getMobileUx()));
        value.put("mobileUx", page.getMobileUx());
        return mapper.valueToTree(value);
    }

    private JsonNode legacyProjection(PageSchemaDTO page) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("pageKey", page.getPageKey());
        value.put("modelCode", page.getModelCode());
        value.put("kind", defaultValue(page.getKind(), "list"));
        value.put("profile", defaultValue(page.getProfile(), "admin"));
        value.put("name", page.getName());
        value.put("title", page.getTitle());
        value.put("description", page.getDescription());
        value.put("layout", page.getLayout());
        value.put("dataSources", page.getDataSources());
        value.put("recordSource", page.getRecordSource());
        value.put("blocks", page.getBlocks() == null ? new ArrayList<>() : page.getBlocks());
        value.put("schemaVersion", page.getSchemaVersion() == null
                ? DslRegistry.PAGE_SCHEMA_CURRENT_VERSION : page.getSchemaVersion());
        value.put("metaInfo", page.getMetaInfo());
        value.put("isTemplate", Boolean.TRUE.equals(page.getIsTemplate()));
        value.put("templateCategory", page.getTemplateCategory());
        value.put("sortWeight", page.getSortWeight() == null ? 0 : page.getSortWeight());
        value.put("extension", extension(page.getExtension(), page.getDataSources(), page.getMobileUx()));
        value.put("mobileUx", page.getMobileUx());
        return mapper.valueToTree(value);
    }

    private Map<String, Object> extension(Map<String, Object> extension,
                                          Map<String, Object> dataSources,
                                          Map<String, Object> mobileUx) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (extension != null) result.putAll(extension);
        if (dataSources != null && !dataSources.isEmpty()) result.put("dataSources", dataSources);
        if (mobileUx != null && !mobileUx.isEmpty()) result.put("mobileUx", mobileUx);
        return result;
    }

    private String digest(JsonNode value) {
        try {
            byte[] encoded = mapper.writeValueAsString(canonical(value)).getBytes(StandardCharsets.UTF_8);
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (NoSuchAlgorithmException | java.io.IOException failure) {
            throw new IllegalStateException("Cannot digest page definition", failure);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode object = mapper.createObjectNode();
            var fields = new TreeMap<String, JsonNode>();
            value.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((key, child) -> object.set(key, canonical(child)));
            return object;
        }
        if (value.isArray()) {
            ArrayNode array = mapper.createArrayNode();
            value.forEach(child -> array.add(canonical(child)));
            return array;
        }
        return value;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String safeMessage(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return "no-message";
        return message.length() <= 160 ? message : message.substring(0, 160);
    }
}
