package com.auraboot.framework.meta.converter;

import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extension converter for converting between Map and ExtensionBean
 * 
 * This converter handles the bidirectional conversion between:
 * - Map<String, Object> (used in DTOs and API requests)
 * - ExtensionBean (used in entity persistence with JSONB)
 * 
 * @author AuraBoot Team
 * @since 2.1.2
 */
@Slf4j
@Component
public class ExtensionConverter {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Convert Map to ExtensionBean
     * 
     * @param map Extension data as Map
     * @return ExtensionBean instance, or null if input is null/empty
     */
    public ExtensionBean toBean(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }

        try {
            ExtensionBean bean = new ExtensionBean();
            bean.setExtension(new LinkedHashMap<>(map));
            return bean;
        } catch (Exception e) {
            log.warn("Failed to convert Map to ExtensionBean", e);
            return null;
        }
    }

    /**
     * Convert ExtensionBean to Map
     * 
     * @param bean ExtensionBean instance
     * @return Extension data as Map, or null if input is null
     */
    public Map<String, Object> toMap(ExtensionBean bean) {
        if (bean == null) {
            return null;
        }

        try {
            // ExtensionBean already contains a Map, return it directly
            Map<String, Object> extension = bean.getExtension();
            if (extension == null || extension.isEmpty()) {
                return null;
            }
            
            // Return a copy to prevent external modification
            return new LinkedHashMap<>(extension);
        } catch (Exception e) {
            log.warn("Failed to convert ExtensionBean to Map", e);
            return null;
        }
    }

    /**
     * Convert ExtensionBean to Map using JSON serialization
     * This method ensures proper type conversion through JSON round-trip
     * 
     * @param bean ExtensionBean instance
     * @return Extension data as Map, or null if conversion fails
     */
    public Map<String, Object> toMapViaJson(ExtensionBean bean) {
        if (bean == null) {
            return null;
        }

        try {
            String json = objectMapper.writeValueAsString(bean);
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to convert ExtensionBean to Map via JSON", e);
            return null;
        }
    }
}
