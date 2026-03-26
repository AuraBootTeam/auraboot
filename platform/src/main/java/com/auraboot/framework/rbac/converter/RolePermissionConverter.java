package com.auraboot.framework.rbac.converter;

import com.auraboot.framework.common.converter.UtcDateTimeMapper;
import com.auraboot.framework.common.util.JsonUtil;
import com.auraboot.framework.rbac.dto.RolePermissionDTO;
import com.auraboot.framework.rbac.entity.RolePermission;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;
import java.util.Map;

/**
 * RolePermission Converter - 使用MapStruct进行Bean转换
 * 
 * @author Kiro
 * @since 2025-01-07
 */
@Mapper(
    componentModel = "spring",
    nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
    uses = UtcDateTimeMapper.class
)
public interface RolePermissionConverter {
    
    /**
     * Entity转DTO
     */
    @Mapping(target = "conditions", expression = "java(objectToMap(entity.getConditions()))")
    @Mapping(target = "roleName", ignore = true)
    @Mapping(target = "permissionCode", ignore = true)
    @Mapping(target = "permissionName", ignore = true)
    RolePermissionDTO toDTO(RolePermission entity);
    
    /**
     * Entity列表转DTO列表
     */
    List<RolePermissionDTO> toDTOList(List<RolePermission> entities);
    
    /**
     * Convert Object to Map
     */
    default Map<String, Object> objectToMap(Object obj) {
        return JsonUtil.toMap(obj);
    }
}