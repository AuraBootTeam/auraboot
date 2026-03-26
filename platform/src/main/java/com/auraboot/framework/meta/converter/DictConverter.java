package com.auraboot.framework.meta.converter;

import com.auraboot.framework.common.converter.UtcDateTimeMapper;
import com.auraboot.framework.meta.dto.DictCreateRequest;
import com.auraboot.framework.meta.dto.DictDTO;
import com.auraboot.framework.meta.dto.DictUpdateRequest;
import com.auraboot.framework.meta.entity.Dict;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mapstruct.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * Dict 实体与 DTO 之间的转换器
 * 使用 MapStruct 框架实现对象映射
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true), uses = UtcDateTimeMapper.class)
public abstract class DictConverter {

    @Autowired
    protected ObjectMapper objectMapper;

    /**
     * 实体转换为 DTO
     * 
     * @param entity Dict 实体
     * @return DictDTO
     */
    @Mapping(target = "id", source = "id")
    @Mapping(target = "pid", source = "pid")
    @Mapping(target = "tenantId", source = "tenantId")
      
    
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "dictType", source = "dictType", qualifiedByName = "mapDictTypeToFrontend")
    @Mapping(target = "version", source = "version")
    @Mapping(target = "semver", source = "semver")
    @Mapping(target = "isCurrent", source = "isCurrent")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    // 设置不存在的字段为默认值
    @Mapping(target = "items", source = "items")
    @Mapping(target = "versionStrategy", constant = "latest")
    @Mapping(target = "pinnedVersion", ignore = true)
    @Mapping(target = "cascadeConfig", ignore = true)
    @Mapping(target = "cacheConfig", ignore = true)
    @Mapping(target = "extendedProps", source = "extension", qualifiedByName = "extensionToJsonNode")
    @Mapping(target = "sortWeight", constant = "0")
    @Mapping(target = "tags", ignore = true)
    @Mapping(target = "enabled", expression = "java(\"PUBLISHED\".equals(entity.getStatus()))")
    @Mapping(target = "isSystem", constant = "false")
    @Mapping(target = "isPublished", expression = "java(\"PUBLISHED\".equals(entity.getStatus()))")
    @Mapping(target = "publishedAt", ignore = true)
    @Mapping(target = "versionNote", ignore = true)
    @Mapping(target = "remark", ignore = true)
    @Mapping(target = "success", ignore = true)
    @Mapping(target = "message", ignore = true)
    @Mapping(target = "deletedFlag", constant = "false")
    @Mapping(target = "rowVersion", constant = "1")
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    public abstract DictDTO toDTO(Dict entity);

    /**
     * 创建请求转换为实体
     * 
     * @param request DictCreateRequest
     * @return Dict 实体
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "pid", ignore = true) // 由服务层生成

    
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "dictType", source = "dictType")
    @Mapping(target = "status", constant = "published") // 新创建的默认发布
    @Mapping(target = "version", constant = "1") // 新创建的版本为1
    @Mapping(target = "semver", constant = "1.0.0")
    @Mapping(target = "isCurrent", constant = "true") // 新创建的为当前版本
    @Mapping(target = "items", ignore = true) // 由服务层处理
    @Mapping(target = "extension", ignore = true) // 由服务层处理
    @Mapping(target = "createdAt", ignore = true) // 由构造函数设置
    @Mapping(target = "updatedAt", ignore = true) // 由构造函数设置
    public abstract Dict toEntity(DictCreateRequest request);

    /**
     * 更新请求转换为实体（部分更新）
     * 
     * @param target 目标实体
     * @param request DictUpdateRequest
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "pid", ignore = true)
    @Mapping(target = "tenantId", ignore = true)
    @Mapping(target = "code", ignore = true) // 编码不允许更新
    @Mapping(target = "name", source = "name")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "dictType", source = "dictType")
    @Mapping(target = "status", ignore = true) // 状态由服务层控制
    @Mapping(target = "version", ignore = true) // 版本由服务层控制
    @Mapping(target = "semver", ignore = true)
    @Mapping(target = "isCurrent", ignore = true) // 当前版本标记由服务层控制
    @Mapping(target = "items", ignore = true) // 字典项由服务层处理
    @Mapping(target = "extension", ignore = true) // 扩展属性由服务层处理
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true) // 由数据库自动更新
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    public abstract void updateEntity(@MappingTarget Dict target, DictUpdateRequest request);

    /**
     * 实体列表转换为 DTO 列表
     * 
     * @param entities Dict 实体列表
     * @return DictDTO 列表
     */
    public abstract List<DictDTO> toDTOList(List<Dict> entities);

    /**
     * JsonNode转换为字符串
     * 
     * @param jsonNode JsonNode对象
     * @return JSON字符串
     */
    @Named("jsonNodeToString")
    public String jsonNodeToString(JsonNode jsonNode) {
        if (jsonNode == null || jsonNode.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(jsonNode);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 字符串转换为JsonNode
     * 
     * @param jsonString JSON字符串
     * @return JsonNode对象
     */
    @Named("stringToJsonNode")
    public JsonNode stringToJsonNode(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readTree(jsonString);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ExtensionBean转换为JsonNode
     * 
     * @param extension ExtensionBean对象
     * @return JsonNode对象
     */
    @Named("extensionToJsonNode")
    public JsonNode extensionToJsonNode(com.auraboot.framework.meta.entity.payload.ExtensionBean extension) {
        if (extension == null) {
            return null;
        }
        try {
            return objectMapper.valueToTree(extension);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Integer转换为Boolean
     * 
     * @param value Integer值
     * @return Boolean值 (1 -> true, 0 -> false)
     */
    @Named("integerToBoolean")
    public Boolean integerToBoolean(Integer value) {
        if (value == null) {
            return null;
        }
        return value == 1;
    }

    /**
     * 映射后端字典类型到前端字典类型
     * DYNAMIC -> SIMPLE (普通字典)
     * TREE -> TREE (树形字典)
     * STATIC -> SIMPLE (兼容旧数据)
     * CASCADE -> TREE (兼容旧数据)
     * 
     * @param backendType 后端字典类型
     * @return 前端字典类型
     */
    @Named("mapDictTypeToFrontend")
    public String mapDictTypeToFrontend(String backendType) {
        if (backendType == null) {
            return "simple";
        }
        return switch (backendType.toLowerCase()) {
            case "dynamic", "static" -> "simple";
            case "tree", "cascade" -> "tree";
            default -> "simple"; // 默认为 simple
        };
    }
}
