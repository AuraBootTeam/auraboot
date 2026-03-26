package com.auraboot.framework.permission.converter;

import com.auraboot.framework.common.converter.UtcDateTimeMapper;
import com.auraboot.framework.permission.dto.SubjectPermissionCreateRequest;
import com.auraboot.framework.permission.dto.SubjectPermissionDTO;
import com.auraboot.framework.permission.entity.SubjectPermission;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

/**
 * SubjectPermission Converter - 使用MapStruct进行Bean转换
 * 
 * @author Kiro
 * @since 2025-01-07
 */
@Mapper(
    componentModel = "spring",
    nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE,
    uses = UtcDateTimeMapper.class
)
public interface SubjectPermissionConverter {
    
    /**
     * Entity转DTO
     */
    @Mapping(target = "permissionCode", ignore = true)
    @Mapping(target = "permissionName", ignore = true)
    SubjectPermissionDTO toDTO(SubjectPermission entity);
    
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
    SubjectPermission toEntity(SubjectPermissionCreateRequest request);
    
    /**
     * Entity列表转DTO列表
     */
    List<SubjectPermissionDTO> toDTOList(List<SubjectPermission> entities);
}