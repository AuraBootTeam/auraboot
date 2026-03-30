package com.auraboot.framework.meta.converter;

import com.auraboot.framework.common.converter.UtcDateTimeMapper;
import com.auraboot.framework.meta.dto.PageSchemaCreateRequest;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.dto.PageSchemaListDTO;
import com.auraboot.framework.meta.dto.PageSchemaUpdateRequest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

/**
 * PageSchema entity/DTO converter.
 * Uses MapStruct with custom JSON serialisation helpers.
 */
@Mapper(componentModel = "spring", uses = UtcDateTimeMapper.class)
public abstract class PageSchemaConverter {

    @Autowired
    protected ObjectMapper objectMapper;

    // ── Entity → DTO ──────────────────────────────────────────────

    @Mapping(target = "pid", source = "pid")
    @Mapping(target = "pageKey", source = "pageKey")
    @Mapping(target = "modelCode", source = "modelCode")
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "profile", source = "profile")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "title", source = "title", qualifiedByName = "stringToMap")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "layout", source = "layout", qualifiedByName = "stringToMap")
    @Mapping(target = "blocks", source = "blocks", qualifiedByName = "stringToList")
    @Mapping(target = "metaInfo", source = "metaInfo", qualifiedByName = "stringToMap")
    @Mapping(target = "isTemplate", source = "isTemplate")
    @Mapping(target = "templateCategory", source = "templateCategory")
    @Mapping(target = "sortWeight", source = "sortWeight")
    @Mapping(target = "publishedAt", source = "publishedAt")
    @Mapping(target = "tags", source = "tags", qualifiedByName = "stringToMap")
    @Mapping(target = "version", source = "version")
    @Mapping(target = "semver", source = "semver")
    @Mapping(target = "rowVersion", source = "rowVersion")
    @Mapping(target = "isCurrent", source = "isCurrent")
    @Mapping(target = "schemaVersion", source = "schemaVersion")
    @Mapping(target = "modelCategory", ignore = true) // enriched at query time
    @Mapping(target = "extension", ignore = true)
    @Mapping(target = "deletedFlag", source = "deletedFlag")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    public abstract PageSchemaDTO toDTO(PageSchema entity);

    // ── CreateRequest → Entity ────────────────────────────────────

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "pid", ignore = true)
    @Mapping(target = "pageKey", source = "pageKey")
    @Mapping(target = "modelCode", source = "modelCode")
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "profile", source = "profile")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "title", source = "title", qualifiedByName = "titleStringToJsonb")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "layout", source = "layout", qualifiedByName = "mapToString")
    @Mapping(target = "blocks", source = "blocks", qualifiedByName = "listToString")
    @Mapping(target = "metaInfo", source = "metaInfo", qualifiedByName = "mapToString")
    @Mapping(target = "isTemplate", source = "isTemplate")
    @Mapping(target = "templateCategory", source = "templateCategory")
    @Mapping(target = "sortWeight", source = "sortWeight")
    @Mapping(target = "publishedAt", ignore = true)
    @Mapping(target = "tags", source = "tags", qualifiedByName = "mapToString")
    @Mapping(target = "version", constant = "1")
    @Mapping(target = "semver", source = "semver")
    @Mapping(target = "rowVersion", constant = "1")
    @Mapping(target = "isCurrent", constant = "true")
    @Mapping(target = "schemaVersion", constant = "2")
    @Mapping(target = "extension", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "deletedFlag", constant = "false")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    public abstract PageSchema toEntity(PageSchemaCreateRequest request);

    // ── UpdateRequest → Entity (partial) ──────────────────────────

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "pid", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "pageKey", source = "pageKey")
    @Mapping(target = "modelCode", source = "modelCode")
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "profile", source = "profile")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "title", source = "title", qualifiedByName = "titleStringToJsonb")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "layout", source = "layout", qualifiedByName = "mapToString")
    @Mapping(target = "blocks", source = "blocks", qualifiedByName = "listToString")
    @Mapping(target = "metaInfo", source = "metaInfo", qualifiedByName = "mapToString")
    @Mapping(target = "isTemplate", source = "isTemplate")
    @Mapping(target = "templateCategory", source = "templateCategory")
    @Mapping(target = "sortWeight", source = "sortWeight")
    @Mapping(target = "publishedAt", ignore = true)
    @Mapping(target = "tags", source = "tags", qualifiedByName = "mapToString")
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "semver", source = "semver")
    @Mapping(target = "rowVersion", ignore = true)
    @Mapping(target = "isCurrent", ignore = true)
    @Mapping(target = "extension", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "deletedFlag", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    public abstract void updateEntity(@MappingTarget PageSchema target, PageSchemaUpdateRequest request);

    // ── Entity list → DTO list ────────────────────────────────────

    public abstract List<PageSchemaDTO> toDTOList(List<PageSchema> entities);

    // ── Entity → ListDTO (lightweight, no blocks/layout) ──────────

    @Mapping(target = "pid", source = "pid")
    @Mapping(target = "pageKey", source = "pageKey")
    @Mapping(target = "modelCode", source = "modelCode")
    @Mapping(target = "kind", source = "kind")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "title", source = "title", qualifiedByName = "jsonbTitleToString")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "metaInfo", source = "metaInfo", qualifiedByName = "stringToMap")
    @Mapping(target = "isTemplate", source = "isTemplate")
    @Mapping(target = "templateCategory", source = "templateCategory")
    @Mapping(target = "sortWeight", source = "sortWeight")
    @Mapping(target = "publishedAt", source = "publishedAt")
    @Mapping(target = "tags", source = "tags", qualifiedByName = "stringToMap")
    @Mapping(target = "version", source = "version")
    @Mapping(target = "semver", source = "semver")
    @Mapping(target = "rowVersion", source = "rowVersion")
    @Mapping(target = "isCurrent", source = "isCurrent")
    @Mapping(target = "deletedFlag", source = "deletedFlag")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    public abstract PageSchemaListDTO toListDTO(PageSchema entity);

    public abstract List<PageSchemaListDTO> toListDTOList(List<PageSchema> entities);

    // ── JSON conversion helpers ───────────────────────────────────

    @Named("stringToMap")
    public Map<String, Object> stringToMap(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(jsonString, Map.class);
        } catch (Exception e) {
            // CATCH: non-transactional JSON parsing, safe to handle
            return null;
        }
    }

    @Named("mapToString")
    public String mapToString(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            // CATCH: non-transactional JSON serialisation, safe to handle
            return null;
        }
    }

    @Named("stringToList")
    public List<Object> stringToList(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(jsonString, new TypeReference<List<Object>>() {});
        } catch (Exception e) {
            // CATCH: non-transactional JSON parsing, safe to handle
            return null;
        }
    }

    @Named("listToString")
    public String listToString(List<Object> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            // CATCH: non-transactional JSON serialisation, safe to handle
            return null;
        }
    }

    /**
     * Convert a plain title string (from CreateRequest/UpdateRequest) to JSONB storage format.
     * Wraps as {"en": "value"}.
     */
    @Named("titleStringToJsonb")
    public String titleStringToJsonb(String title) {
        if (title == null || title.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(Map.of("en", title));
        } catch (Exception e) {
            // CATCH: non-transactional JSON serialisation, safe to handle
            return null;
        }
    }

    /**
     * Extract a display-friendly title string from JSONB storage.
     * Preference order: zh-CN, en, first available value.
     */
    @Named("jsonbTitleToString")
    public String jsonbTitleToString(String jsonbTitle) {
        if (jsonbTitle == null || jsonbTitle.trim().isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(jsonbTitle, Map.class);
            if (map.containsKey("zh-CN")) return map.get("zh-CN").toString();
            if (map.containsKey("en")) return map.get("en").toString();
            return map.values().stream().findFirst().map(Object::toString).orElse(null);
        } catch (Exception e) {
            // CATCH: non-transactional — fall back to raw string if not valid JSON
            return jsonbTitle;
        }
    }

    @Named("booleanToInteger")
    public Integer booleanToInteger(Boolean value) {
        if (value == null) {
            return null;
        }
        return value ? 1 : 0;
    }
}
