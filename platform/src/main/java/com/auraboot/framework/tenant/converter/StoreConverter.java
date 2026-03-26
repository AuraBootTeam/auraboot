package com.auraboot.framework.tenant.converter;

import com.auraboot.framework.common.converter.UtcDateTimeMapper;
import com.auraboot.framework.tenant.dao.entity.Store;
import com.auraboot.framework.tenant.dto.StoreCreateRequest;
import com.auraboot.framework.tenant.dto.StoreUpdateRequest;
import com.auraboot.framework.tenant.dto.StoreResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.*;

import java.util.Map;

/**
 * Store实体转换器
 * 使用MapStruct进行实体和DTO之间的转换
 */
@Mapper(componentModel = "spring", uses = UtcDateTimeMapper.class)
public interface StoreConverter {

    /**
     * 将Store实体转换为StoreResponse DTO
     *
     * @param store Store实体
     * @return StoreResponse DTO
     */
    @Mapping(target = "extension", source = "extension", qualifiedByName = "stringToMap")
    StoreResponse toResponse(Store store);

    /**
     * 将StoreCreateRequest DTO转换为Store实体
     *
     * @param request StoreCreateRequest DTO
     * @return Store实体
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "pid", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deletedFlag", ignore = true)
    @Mapping(target = "extension", source = "extension", qualifiedByName = "mapToString")
    Store toEntity(StoreCreateRequest request);

    /**
     * 使用StoreUpdateRequest更新Store实体
     *
     * @param store Store实体（目标对象）
     * @param request StoreUpdateRequest DTO（源对象）
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "pid", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deletedFlag", ignore = true)
    @Mapping(target = "extension", source = "extension", qualifiedByName = "mapToString")
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateEntity(@MappingTarget Store store, StoreUpdateRequest request);

    /**
     * 将Map转换为JSON字符串
     */
    @Named("mapToString")
    default String mapToString(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * 将JSON字符串转换为Map
     */
    @Named("stringToMap")
    @SuppressWarnings("unchecked")
    default Map<String, Object> stringToMap(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
