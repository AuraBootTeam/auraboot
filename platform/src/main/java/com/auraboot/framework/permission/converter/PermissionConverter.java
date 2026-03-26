package com.auraboot.framework.permission.converter;

import com.auraboot.framework.common.converter.UtcDateTimeMapper;
import com.auraboot.framework.common.util.JsonUtil;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.dto.PermissionUpdateRequest;
import com.auraboot.framework.permission.entity.Permission;
import com.fasterxml.jackson.core.type.TypeReference;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;
import java.util.Map;

/**
 * Permission Converter - 使用MapStruct进行Bean转换
 * 
 * @author Kiro
 * @since 2025-01-07
 */
@Mapper(
    componentModel = "spring",
    nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
    uses = UtcDateTimeMapper.class
)
public interface PermissionConverter {
    
    /**
     * Entity转DTO
     */
    @Mapping(target = "dataScopeConfig", expression = "java(objectToMap(entity.getDataScopeConfig()))")
    @Mapping(target = "extension", expression = "java(objectToMap(entity.getExtension()))")
    PermissionDTO toDTO(Permission entity);
    
    /**
     * CreateRequest转Entity
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "pid", ignore = true)
    @Mapping(target = "tenantId", ignore = true)

    @Mapping(target = "status", constant = "active")
    @Mapping(target = "deletedFlag", constant = "false")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deprecatedAt", ignore = true)
    @Mapping(target = "archivedAt", ignore = true)
    @Mapping(target = "path", ignore = true)
    @Mapping(target = "level", ignore = true)
    Permission toEntity(PermissionCreateRequest request);
    
    /**
     * UpdateRequest更新Entity
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "pid", ignore = true)
    @Mapping(target = "tenantId", ignore = true)

    @Mapping(target = "code", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "deletedFlag", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "deprecatedAt", ignore = true)
    @Mapping(target = "archivedAt", ignore = true)
    @Mapping(target = "resourceType", ignore = true)
    @Mapping(target = "resourceCode", ignore = true)
    @Mapping(target = "action", ignore = true)
    @Mapping(target = "source", ignore = true)
    @Mapping(target = "sourceRef", ignore = true)
    @Mapping(target = "parentId", ignore = true)
    @Mapping(target = "path", ignore = true)
    @Mapping(target = "level", ignore = true)
    void updateEntity(@MappingTarget Permission entity, PermissionUpdateRequest request);
    
    /**
     * Entity列表转DTO列表
     */
    List<PermissionDTO> toDTOList(List<Permission> entities);
    
    /**
     * Convert Object to Map
     */
    default Map<String, Object> objectToMap(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof String str) {
            if (str.trim().isEmpty() || "{}".equals(str.trim())) {
                return null;
            }
            try {
                return JsonUtil.parse(str, new TypeReference<>() {});
            } catch (Exception e) {
                return null;
            }
        }
        try {
            return JsonUtil.toMap(obj);
        } catch (Exception e) {
            return null;
        }
    }
}