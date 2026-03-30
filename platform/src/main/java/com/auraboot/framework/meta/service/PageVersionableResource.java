package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.versioning.VersionableResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Page Schema implementation of VersionableResource (GAP-023).
 * Creates and applies snapshots for page design version history.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PageVersionableResource implements VersionableResource {

    private final PageSchemaMapper pageSchemaMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getResourceType() {
        return "page";
    }

    @Override
    public JsonNode createSnapshot(String resourceId) {
        PageSchema page = pageSchemaMapper.selectByPageKey(resourceId);
        if (page == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Page not found: " + resourceId);
        }

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("pageKey", page.getPageKey());
        snapshot.put("modelCode", page.getModelCode());
        snapshot.put("name", page.getName());
        snapshot.put("title", page.getTitle());
        snapshot.put("description", page.getDescription());
        snapshot.put("kind", page.getKind());
        snapshot.put("schemaVersion", page.getSchemaVersion());
        if (page.getBlocks() != null) {
            try {
                snapshot.set("blocks", objectMapper.readTree(page.getBlocks()));
            } catch (Exception e) {
                snapshot.put("blocks", page.getBlocks());
            }
        }
        if (page.getLayout() != null) {
            try {
                snapshot.set("layout", objectMapper.readTree(page.getLayout()));
            } catch (Exception e) {
                snapshot.put("layout", page.getLayout());
            }
        }
        if (page.getMetaInfo() != null) {
            try {
                snapshot.set("metaInfo", objectMapper.readTree(page.getMetaInfo()));
            } catch (Exception e) {
                snapshot.put("metaInfo", page.getMetaInfo());
            }
        }

        return snapshot;
    }

    @Override
    public void applySnapshot(String resourceId, JsonNode snapshot) {
        PageSchema page = pageSchemaMapper.selectByPageKey(resourceId);
        if (page == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Page not found: " + resourceId);
        }

        if (snapshot.has("title")) page.setTitle(snapshot.get("title").asText());
        if (snapshot.has("description")) page.setDescription(snapshot.path("description").asText(null));
        if (snapshot.has("kind")) page.setKind(snapshot.get("kind").asText());
        if (snapshot.has("blocks")) {
            page.setBlocks(snapshot.get("blocks").toString());
        }
        if (snapshot.has("layout")) {
            page.setLayout(snapshot.get("layout").toString());
        }
        if (snapshot.has("metaInfo")) {
            page.setMetaInfo(snapshot.get("metaInfo").toString());
        }

        pageSchemaMapper.updateById(page);
        log.info("Applied version snapshot to page: {}", resourceId);
    }
}
