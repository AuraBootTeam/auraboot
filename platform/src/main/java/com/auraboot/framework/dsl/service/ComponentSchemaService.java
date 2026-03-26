package com.auraboot.framework.dsl.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.dsl.dto.ComponentSchemaDTO;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service that loads and serves component property schemas from a bundled JSON resource.
 * The schemas are loaded once at startup and cached in memory.
 */
@Service
public class ComponentSchemaService {

    private static final Logger log = LoggerFactory.getLogger(ComponentSchemaService.class);
    private static final String RESOURCE_PATH = "component-schemas.json";

    private final ObjectMapper objectMapper;

    /** type -> ComponentSchemaDTO, unmodifiable after init */
    private Map<String, ComponentSchemaDTO> componentMap = Collections.emptyMap();

    private String version;

    public ComponentSchemaService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void init() {
        try {
            ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
            try (InputStream is = resource.getInputStream()) {
                Map<String, Object> root = objectMapper.readValue(is, new TypeReference<>() {});
                this.version = (String) root.getOrDefault("version", "unknown");

                Map<String, Object> components = (Map<String, Object>) root.get("components");
                if (components == null) {
                    log.warn("No components found in {}", RESOURCE_PATH);
                    return;
                }

                Map<String, ComponentSchemaDTO> map = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : components.entrySet()) {
                    String type = entry.getKey();
                    Map<String, Object> raw = (Map<String, Object>) entry.getValue();

                    ComponentSchemaDTO dto = new ComponentSchemaDTO();
                    dto.setType(type);
                    dto.setName((String) raw.get("name"));
                    dto.setCategory((String) raw.get("category"));
                    dto.setDescription((String) raw.get("description"));
                    dto.setCompatibleDataTypes((List<String>) raw.get("compatibleDataTypes"));
                    dto.setProperties((List<Map<String, Object>>) raw.get("properties"));
                    dto.setTags((List<String>) raw.get("tags"));

                    map.put(type, dto);
                }

                this.componentMap = Collections.unmodifiableMap(map);
                log.info("Loaded {} component schemas (version {})", map.size(), version);
            }
        } catch (IOException e) {
            log.error("Failed to load component schemas from {}", RESOURCE_PATH, e);
            throw new IllegalStateException("Cannot load component-schemas.json", e);
        }
    }

    /**
     * Get all component schemas.
     */
    public Collection<ComponentSchemaDTO> getAllComponents() {
        return componentMap.values();
    }

    /**
     * Get a single component schema by type.
     *
     * @return the schema or null if not found
     */
    public ComponentSchemaDTO getComponent(String type) {
        return componentMap.get(type);
    }

    /**
     * Filter components by compatible data type.
     */
    public List<ComponentSchemaDTO> getComponentsByDataType(String dataType) {
        if (dataType == null || dataType.isBlank()) {
            return List.of();
        }
        String lower = dataType.toLowerCase(Locale.ROOT);
        return componentMap.values().stream()
                .filter(c -> c.getCompatibleDataTypes() != null
                        && c.getCompatibleDataTypes().stream()
                        .filter(Objects::nonNull)
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .anyMatch(lower::equals))
                .collect(Collectors.toList());
    }

    /**
     * Filter components by category.
     */
    public List<ComponentSchemaDTO> getComponentsByCategory(String category) {
        String lower = category.toLowerCase();
        return componentMap.values().stream()
                .filter(c -> lower.equals(c.getCategory()))
                .collect(Collectors.toList());
    }

    /**
     * Get the schema version string.
     */
    public String getVersion() {
        return version;
    }

    /**
     * Get total number of registered components.
     */
    public int getComponentCount() {
        return componentMap.size();
    }
}
