package com.auraboot.framework.meta.converter;

import com.auraboot.framework.common.converter.UtcDateTimeMapper;
import com.auraboot.framework.meta.dto.PageSchemaCreateRequest;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.dto.PageSchemaListDTO;
import com.auraboot.framework.meta.dto.PageSchemaUpdateRequest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

/**
 * PageSchema 实体与 DTO 之间的转换器
 * 使用 MapStruct 框架实现对象映射
 */
@Mapper(componentModel = "spring", uses = UtcDateTimeMapper.class)
public abstract class PageSchemaConverter {

    @Autowired
    protected ObjectMapper objectMapper;

    /**
     * 实体转换为 DTO
     * 
     * @param entity PageSchema 实体
     * @return PageSchemaDTO
     */
    @Mapping(target = "pid", source = "pid")
    @Mapping(target = "pageKey", source = "pageKey")
    @Mapping(target = "modelCode", source = "modelCode")
    @Mapping(target = "pageCategory", source = "pageCategory")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "pageType", source = "pageType")
    @Mapping(target = "dslSchema", source = "dslSchema", qualifiedByName = "stringToMap")
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

    @Mapping(target = "extension", ignore = true) // 忽略 extension 字段映射
    @Mapping(target = "deletedFlag", source = "deletedFlag")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    public abstract PageSchemaDTO toDTO(PageSchema entity);

    /**
     * 创建请求转换为实体
     * 
     * @param request PageSchemaCreateRequest
     * @return PageSchema 实体
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "pid", ignore = true) // 由服务层生成
    @Mapping(target = "pageKey", source = "pageKey")
    @Mapping(target = "modelCode", source = "modelCode")
    @Mapping(target = "pageCategory", source = "pageCategory")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "pageType", source = "pageType")
    @Mapping(target = "dslSchema", source = "dslSchema", qualifiedByName = "mapToString")
    @Mapping(target = "metaInfo", source = "metaInfo", qualifiedByName = "mapToString")
    @Mapping(target = "isTemplate", source = "isTemplate")
    @Mapping(target = "templateCategory", source = "templateCategory")
    @Mapping(target = "sortWeight", source = "sortWeight")
    @Mapping(target = "publishedAt", ignore = true)
    @Mapping(target = "tags", source = "tags", qualifiedByName = "mapToString")
    @Mapping(target = "version", constant = "1") // 新创建的版本为1
    @Mapping(target = "semver", source = "semver")
    @Mapping(target = "rowVersion", constant = "1")
    @Mapping(target = "isCurrent", constant = "true") // 新创建的为当前版本
    @Mapping(target = "schemaVersion", constant = "1") // DSL schema format version baseline

    @Mapping(target = "extension", ignore = true) // 忽略 extension 字段映射
    @Mapping(target = "status", ignore = true) // 由服务层设置
    @Mapping(target = "deletedFlag", constant = "false")
    @Mapping(target = "createdAt", ignore = true) // 由数据库自动设置
    @Mapping(target = "updatedAt", ignore = true) // 由数据库自动设置
    public abstract PageSchema toEntity(PageSchemaCreateRequest request);

    /**
     * 更新请求转换为实体（部分更新）
     * 
     * @param target 目标实体
     * @param request PageSchemaUpdateRequest
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "pid", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "pageKey", source = "pageKey")
    @Mapping(target = "modelCode", source = "modelCode")
    @Mapping(target = "pageCategory", source = "pageCategory")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "pageType", source = "pageType")
    @Mapping(target = "dslSchema", source = "dslSchema", qualifiedByName = "mapToString")
    @Mapping(target = "metaInfo", source = "metaInfo", qualifiedByName = "mapToString")
    @Mapping(target = "isTemplate", source = "isTemplate")
    @Mapping(target = "templateCategory", source = "templateCategory")
    @Mapping(target = "sortWeight", source = "sortWeight")
    @Mapping(target = "publishedAt", ignore = true) // 由服务层控制
    @Mapping(target = "tags", source = "tags", qualifiedByName = "mapToString")
    @Mapping(target = "version", ignore = true) // 版本由服务层控制
    @Mapping(target = "semver", source = "semver")
    @Mapping(target = "rowVersion", ignore = true) // 行版本由数据库控制
    @Mapping(target = "isCurrent", ignore = true) // 当前版本标记由服务层控制
      
    @Mapping(target = "extension", ignore = true) // 忽略 extension 字段映射
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "deletedFlag", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true) // 由数据库自动更新
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    public abstract void updateEntity(@MappingTarget PageSchema target, PageSchemaUpdateRequest request);

    /**
     * 实体列表转换为 DTO 列表
     *
     * @param entities PageSchema 实体列表
     * @return PageSchemaDTO 列表
     */
    public abstract List<PageSchemaDTO> toDTOList(List<PageSchema> entities);

    /**
     * 实体转换为列表 DTO（不包含 dslSchema）
     *
     * @param entity PageSchema 实体
     * @return PageSchemaListDTO
     */
    @Mapping(target = "pid", source = "pid")
    @Mapping(target = "pageKey", source = "pageKey")
    @Mapping(target = "modelCode", source = "modelCode")
    @Mapping(target = "pageCategory", source = "pageCategory")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "pageType", source = "pageType")
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

    /**
     * 实体列表转换为列表 DTO 列表（不包含 dslSchema）
     *
     * @param entities PageSchema 实体列表
     * @return PageSchemaListDTO 列表
     */
    public abstract List<PageSchemaListDTO> toListDTOList(List<PageSchema> entities);

    /**
     * JSON字符串转换为Map
     * 
     * @param jsonString JSON字符串
     * @return Map对象
     */
    @Named("stringToMap")
    public Map<String, Object> stringToMap(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(jsonString, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Map转换为JSON字符串
     * 
     * @param map Map对象
     * @return JSON字符串
     */
    @Named("mapToString")
    public String mapToString(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Boolean转换为Integer
     * 
     * @param value Boolean值
     * @return Integer值 (true -> 1, false -> 0)
     */
    @Named("booleanToInteger")
    public Integer booleanToInteger(Boolean value) {
        if (value == null) {
            return null;
        }
        return value ? 1 : 0;
    }
}
