package com.auraboot.framework.promotion.reference.service;

import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.promotion.reference.dao.entity.ResourceReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Extracts the set of model / field references contained in a {@link PageSchema}'s DSL content.
 * PoC-grade — no per-DSL-version smarts; recognizes a small set of canonical key names.
 *
 * <ul>
 *   <li>{@code page.modelCode} → MODEL reference (always emitted)</li>
 *   <li>JSON object key {@code modelCode} anywhere in blocks → MODEL reference</li>
 *   <li>JSON object key {@code fieldCode} anywhere in blocks → FIELD reference</li>
 *   <li>JSON object key {@code code} on objects nested under fields/columns/buttons/rowActions
 *       → FIELD reference (best-effort; may overcollect on plugin-specific blocks)</li>
 * </ul>
 */
@Slf4j
@Component
public class ResourceReferenceExtractor {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Object keys whose array members carry an inner {@code code} we treat as a FIELD ref. */
    private static final Set<String> FIELD_CONTAINER_KEYS = Set.of(
            "fields", "columns", "rowActions", "buttons", "filters"
    );

    /**
     * @return de-duplicated set of references implied by the page. Caller is responsible for
     *         persisting via the service.
     */
    public Set<ResourceReference> extract(PageSchema page) {
        Set<ResourceReference> refs = new HashSet<>();
        if (page == null) return refs;

        if (page.getModelCode() != null && !page.getModelCode().isBlank()) {
            refs.add(buildRef(page, "MODEL", page.getModelCode(), "page.modelCode"));
        }

        JsonNode blocks = parseOrNull(page.getBlocks());
        if (blocks != null) {
            walk(blocks, null, page, refs);
        }
        return refs;
    }

    /**
     * @param parentKey  name of the JSON key whose value is {@code node} — used to recognize
     *                   "we're inside a {@code fields:[…]} array" without a stack.
     */
    private void walk(JsonNode node, String parentKey, PageSchema page, Set<ResourceReference> out) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            JsonNode model = node.get("modelCode");
            if (model != null && model.isTextual() && !model.asText().isBlank()) {
                out.add(buildRef(page, "MODEL", model.asText(), "modelCode"));
            }
            JsonNode fc = node.get("fieldCode");
            if (fc != null && fc.isTextual() && !fc.asText().isBlank()) {
                out.add(buildRef(page, "FIELD", fc.asText(), "fieldCode"));
            }
            // `code` inside a fields/columns/etc array is a FIELD ref
            if (parentKey != null && FIELD_CONTAINER_KEYS.contains(parentKey)) {
                JsonNode code = node.get("code");
                if (code != null && code.isTextual() && !code.asText().isBlank()) {
                    out.add(buildRef(page, "FIELD", code.asText(), parentKey + ".code"));
                }
            }
            node.fields().forEachRemaining(e -> walk(e.getValue(), e.getKey(), page, out));
        } else if (node.isArray()) {
            // Array elements inherit the parent key (so children of `fields: [...]` see parentKey="fields")
            for (JsonNode child : node) {
                walk(child, parentKey, page, out);
            }
        }
    }

    private ResourceReference buildRef(PageSchema page, String targetType, String targetCode, String refType) {
        ResourceReference r = new ResourceReference();
        r.setTenantId(page.getTenantId());
        r.setEnvId(page.getEnvId());
        r.setSourceType("PAGE_SCHEMA");
        r.setSourceId(page.getPid());
        r.setTargetType(targetType);
        r.setTargetCode(targetCode);
        r.setRefType(refType);
        r.setDeletedFlag(false);
        return r;
    }

    private JsonNode parseOrNull(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse blocks JSON for reference extraction: {}", e.getMessage());
            return null;
        }
    }
}
