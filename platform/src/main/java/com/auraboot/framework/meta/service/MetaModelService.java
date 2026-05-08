package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.ModelFieldBinding;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 模型元数据服务
 * 职责：提供模型和字段的元数据信息，支持动态服务的运行时需求
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
public interface MetaModelService {

    // ==================== 模型元数据 ====================

    /**
     * 获取模型完整定义（带缓存）
     * @param modelCode 模型编码
     * @return 模型定义，如果不存在返回Optional.empty()
     */
    Optional<ModelDefinition> getModelDefinition(String modelCode);

    /**
     * 强制从数据库获取模型定义（绕过缓存）
     * @param modelCode 模型编码
     * @return 模型定义
     */
    Optional<ModelDefinition> getModelDefinitionFromDb(String modelCode);

    /**
     * 获取模型的表名
     * @param modelCode 模型编码
     * @return 表名
     */
    String getTableName(String modelCode);

    /**
     * 获取模型的字段列表
     * @param modelCode 模型编码
     * @return 字段定义列表
     */
    List<FieldDefinition> getModelFields(String modelCode);

    /**
     * 获取模型的主键字段
     * @param modelCode 模型编码
     * @return 主键字段定义
     */
    FieldDefinition getPrimaryKeyField(String modelCode);

    /**
     * 获取模型的显示字段
     * @param modelCode 模型编码
     * @return 显示字段列表
     */
    List<FieldDefinition> getDisplayFields(String modelCode);

    // ==================== 字段元数据 ====================

    /**
     * 获取字段定义
     * @param modelCode 模型编码
     * @param fieldCode 字段编码
     * @return 字段定义
     */
    FieldDefinition getFieldDefinition(String modelCode, String fieldCode);

    /**
     * 获取字段的数据库列名
     * @param modelCode 模型编码
     * @param fieldCode 字段编码
     * @return 列名
     */
    String getColumnName(String modelCode, String fieldCode);

    /**
     * 获取字段的数据类型映射
     * @param modelCode 模型编码
     * @param fieldCode 字段编码
     * @return 数据类型映射
     */
    DataTypeMapping getFieldDataType(String modelCode, String fieldCode);

    /**
     * 获取字段的验证规则
     * @param modelCode 模型编码
     * @param fieldCode 字段编码
     * @return 验证规则列表
     */
    List<ValidationRule> getFieldValidationRules(String modelCode, String fieldCode);

    // ==================== 关联关系元数据 ====================

    /**
     * 获取模型的关联关系
     * @param modelCode 模型编码
     * @return 关联关系列表
     */
    List<RelationDefinition> getModelRelations(String modelCode);

    /**
     * 获取指定关联关系的定义
     * @param modelCode 模型编码
     * @param relationName 关联名称
     * @return 关联关系定义
     */
    RelationDefinition getRelationDefinition(String modelCode, String relationName);

    /**
     * 获取反向关联关系
     * @param modelCode 模型编码
     * @param relationName 关联名称
     * @return 反向关联关系定义
     */
    RelationDefinition getReverseRelation(String modelCode, String relationName);

    // ==================== 索引和约束元数据 ====================

    /**
     * 获取模型的索引定义
     * @param modelCode 模型编码
     * @return 索引定义列表
     */
    List<IndexDefinition> getModelIndexes(String modelCode);

    /**
     * 获取模型的约束定义
     * @param modelCode 模型编码
     * @return 约束定义列表
     */
    List<ConstraintDefinition> getModelConstraints(String modelCode);

    /**
     * 获取字段的索引信息
     * @param modelCode 模型编码
     * @param fieldCode 字段编码
     * @return 索引信息列表
     */
    List<IndexInfo> getFieldIndexes(String modelCode, String fieldCode);

    // ==================== 查询构建支持 ====================

    /**
     * Build base query SQL.
     *
     * @param modelCode model code
     * @param queryType query type
     * @return SQL builder
     * @deprecated use QueryBuilderService instead
     */
    @Deprecated
    QueryBuilderService.QueryBuilder buildBaseQuery(String modelCode, QueryBuilderService.QueryType queryType);

    /**
     * Build conditional query SQL.
     *
     * @param modelCode model code
     * @param conditions query conditions
     * @return SQL builder
     * @deprecated use QueryBuilderService instead
     */
    @Deprecated
    QueryBuilderService.QueryBuilder buildConditionQuery(String modelCode, List<QueryCondition> conditions);

    /**
     * Build ORDER BY SQL.
     *
     * @param modelCode model code
     * @param sortFields sort fields
     * @return ORDER BY clause
     * @deprecated use QueryBuilderService instead
     */
    @Deprecated
    String buildOrderByClause(String modelCode, List<SortField> sortFields);

    /**
     * Build pagination SQL.
     *
     * @param baseQuery base query
     * @param pageRequest pagination request
     * @return pagination SQL
     * @deprecated use QueryBuilderService instead
     */
    @Deprecated
    String buildPaginationQuery(String baseQuery, PaginationRequest pageRequest);

    // ==================== 缓存管理 ====================

    /**
     * 刷新模型元数据缓存
     * @param modelCode 模型编码
     */
    void refreshModelCache(String modelCode);

    /**
     * 清除所有元数据缓存
     */
    void clearAllCache();

    /**
     * 预加载模型元数据
     * @param modelCodes 模型编码列表
     */
    void preloadModels(List<String> modelCodes);

    // ==================== 元数据验证 ====================

    /**
     * 验证模型定义的完整性
     * @param modelCode 模型编码
     * @return 验证结果
     */
    MetadataValidationResult validateModelMetadata(String modelCode);

    /**
     * 检查模型是否存在
     * @param modelCode 模型编码
     * @return 是否存在
     */
    boolean isModelExists(String modelCode);

    /**
     * 检查字段是否存在
     * @param modelCode 模型编码
     * @param fieldCode 字段编码
     * @return 是否存在
     */
    boolean isFieldExists(String modelCode, String fieldCode);

    // ==================== 模型管理 CRUD 操作 ====================

    /**
     * 创建模型
     * @param request 创建请求
     * @return 创建的模型DTO
     */
    MetaModelDTO create(MetaModelCreateRequest request);

    /**
     * Upsert a model definition (Phase 1 virtual-model API).
     *
     * Persists {@code sourceType} / {@code sourceRef} / {@code primaryKey} and
     * normalizes field-level {@code sortable}/{@code filterable} flags into
     * {@code capabilities.sortableFields}/{@code filterableFields} whitelist
     * (the whitelist is the runtime truth; any caller-supplied whitelist is
     * overridden by this normalization).
     *
     * @param def model definition (must have non-blank code)
     * @return persisted definition reloaded from DB
     */
    ModelDefinition saveDefinition(ModelDefinition def);

    /**
     * Lookup a model definition by code. Null-friendly wrapper around
     * {@link #getModelDefinition(String)} for Phase 1 API ergonomics.
     */
    ModelDefinition getDefinitionByCode(String code);

    /**
     * 根据PID查找模型
     * @param pid 模型PID
     * @return 模型DTO
     */
    MetaModelDTO findByPid(String pid);

    /**
     * 删除模型
     * @param pid 模型PID
     */
    void delete(String pid);

    /**
     * 检查模型编码是否唯一
     * @param code 模型编码
     * @param excludePid 排除的PID（用于更新时检查）
     * @return 是否唯一
     */
    boolean isCodeUnique(String code, String excludePid);


    // ==================== 字段绑定管理 ====================

    /**
     * 检查模型是否存在（通过ID）
     * @param modelId 模型ID
     * @return 是否存在
     */
    boolean isModelExists(Long modelId);

    /**
     * 检查字段是否存在（通过ID）
     * @param fieldId 字段ID
     * @return 是否存在
     */
    boolean isFieldExists(Long fieldId);

    /**
     * 检查字段是否已绑定到模型
     * @param modelId 模型ID
     * @param fieldId 字段ID
     * @return 是否已绑定
     */
    boolean isFieldBoundToModel(Long modelId, Long fieldId);

    /**
     * 绑定字段到模型
     * @param modelId 模型ID
     * @param fieldId 字段ID
     * @param fieldOrder 字段顺序
     * @param required 是否必填
     * @param visible 是否可见
     * @param editable 是否可编辑
     * @param defaultValue 默认值
     * @param validationRules 验证规则
     * @param displayConfig 显示配置
     * @param remarks 备注
     * @return 绑定关系
     */
    ModelFieldBinding bindFieldToModel(
            Long modelId, Long fieldId, Integer fieldOrder, Boolean required,
            Boolean visible, Boolean editable, String defaultValue,
            String validationRules, String displayConfig, String remarks);

    /**
     * 从模型解绑字段
     * @param modelId 模型ID
     * @param fieldId 字段ID
     * @return 是否成功
     */
    boolean unbindFieldFromModel(Long modelId, Long fieldId);

    /**
     * 获取模型的字段绑定列表
     * @param modelId 模型ID
     * @param includeDetails 是否包含详细信息
     * @return 字段绑定列表
     */
    java.util.List<ModelFieldBinding> getModelFieldBindings(Long modelId, Boolean includeDetails);

    /**
     * 获取字段绑定关系
     * @param modelId 模型ID
     * @param fieldId 字段ID
     * @return 绑定关系
     */
    java.util.Optional<ModelFieldBinding> getFieldBinding(Long modelId, Long fieldId);

    /**
     * 更新字段绑定关系
     * @param binding 绑定关系
     * @return 更新后的绑定关系
     */
    ModelFieldBinding updateFieldBinding(ModelFieldBinding binding);

    /**
     * 批量更新字段顺序
     * 
     * @deprecated This method has been moved to ModelFieldBindingService.reorderFields()
     * @param modelId 模型ID
     * @param orderUpdates 顺序更新列表
     * @return 更新后的绑定关系列表
     */
    // java.util.List<com.auraboot.framework.meta.entity.ModelFieldBinding> updateFieldsOrder(Long modelId, java.util.List<com.auraboot.framework.meta.controller.config.ModelFieldBindingController.FieldOrderUpdate> orderUpdates);

    // ==================== 版本管理 ====================

    /**
     * 获取模型的版本历史
     * @param code 模型编码
     * @return 版本历史列表
     */
    List<MetaModelDTO> getVersionHistory(String code);

    /**
     * 获取指定版本的模型详情
     * @param code 模型编码
     * @param version 版本号
     * @return 模型详情
     */
    MetaModelDTO getVersionDetail(String code, Integer version);

    /**
     * 对比两个版本
     * @param code 模型编码
     * @param v1 版本1
     * @param v2 版本2
     * @return 版本差异
     */
    Map<String, Object> compareVersions(String code, Integer v1, Integer v2);

    /**
     * 回滚到指定版本
     * @param code 模型编码
     * @param version 目标版本号
     * @return 回滚后的模型DTO
     */
    MetaModelDTO rollbackToVersion(String code, Integer version);

    /**
     * Lookup the current version of a model by code.
     *
     * @param code 模型编码
     * @return 模型DTO, or {@code null} if no model with the given code exists
     */
    MetaModelDTO findByCode(String code);

    /**
     * Lookup the current version of a model by code, throwing when missing.
     *
     * @param code 模型编码
     * @return 模型DTO (never null)
     * @throws com.auraboot.framework.exception.ValidationException if no
     *         model with the given code exists or {@code code} is blank
     */
    MetaModelDTO findByCodeOrThrow(String code);

    /**
     * 分页查询模型列表
     * @param page 页码（从1开始）
     * @param size 每页大小
     * @param keyword 关键词（搜索code、displayName、description）
     * @param code 模型编码精确匹配
     * @param displayName 显示名称模糊匹配
     * @param modelType 模型类型
     * @param status 状态
     * @param sourceType 数据来源类型
     * @param currentOnly 是否只查询当前版本
     * @return 分页结果
     */
    PageResult<MetaModelDTO> searchModels(
            Integer page, Integer size, String keyword, String code, String displayName,
            String modelType, String status, String sourceType, String sortField, String sortOrder, Boolean currentOnly
    );

    /**
     * 获取模型统计信息
     * @return 统计信息
     */
    Map<String, Object> getStatistics();

    /**
     * 验证模型数据
     * @param modelData 模型数据
     * @return 验证结果
     */
    Map<String, Object> validateModelData(Map<String, Object> modelData);

    // ==================== Publish/Unpublish ====================

    /**
     * Publish a model: validate and create the database table
     * @param pid Model PID
     * @param versionNote Optional version note
     * @return Updated model DTO
     */
    MetaModelDTO publish(String pid, String versionNote);

    /**
     * Unpublish a model: mark as DEPRECATED without dropping the table
     * @param pid Model PID
     * @return Updated model DTO
     */
    MetaModelDTO unpublish(String pid);

    /**
     * Preview the DDL statements that will be executed on publish
     * @param pid Model PID
     * @return DDL preview result
     */
    DDLPreviewResult previewPublishDDL(String pid);
}
