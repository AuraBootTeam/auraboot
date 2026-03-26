package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.*;
import java.util.List;

/**
 * 模式管理服务
 * 职责：根据 Model 和 Field 的关系，自动管理数据库表结构
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
public interface SchemaManagementService {

    // ==================== 表结构管理 ====================

    /**
     * 根据模型定义创建表
     * @param modelCode 模型编码
     * @return 创建结果
     */
    SchemaOperationResult createTableByModel(String modelCode);

    /**
     * 根据模型定义更新表结构
     * @param modelCode 模型编码
     * @return 更新结果
     */
    SchemaOperationResult updateTableByModel(String modelCode);

    /**
     * 删除模型对应的表
     * @param modelCode 模型编码
     * @return 删除结果
     */
    SchemaOperationResult dropTableByModel(String modelCode);

    /**
     * 同步模型到数据库表结构
     * @param modelCode 模型编码
     * @param syncOptions 同步选项
     * @return 同步结果
     */
    SchemaOperationResult syncModelToTable(String modelCode, SchemaSyncOptions syncOptions);

    // ==================== 字段管理 ====================

    /**
     * 为模型添加字段
     * @param modelCode 模型编码
     * @param fieldCode 字段编码
     * @return 操作结果
     */
    SchemaOperationResult addFieldToModel(String modelCode, String fieldCode);

    /**
     * 从模型中移除字段
     * @param modelCode 模型编码
     * @param fieldCode 字段编码
     * @return 操作结果
     */
    SchemaOperationResult removeFieldFromModel(String modelCode, String fieldCode);

    /**
     * 更新模型字段定义
     * @param modelCode 模型编码
     * @param fieldCode 字段编码
     * @return 操作结果
     */
    SchemaOperationResult updateModelField(String modelCode, String fieldCode);

    // ==================== 索引和约束管理 ====================

    /**
     * 为模型字段创建索引
     * @param modelCode 模型编码
     * @param fieldCode 字段编码
     * @param indexType 索引类型
     * @return 操作结果
     */
    SchemaOperationResult createFieldIndex(String modelCode, String fieldCode, IndexType indexType);

    /**
     * 删除字段索引
     * @param modelCode 模型编码
     * @param fieldCode 字段编码
     * @return 操作结果
     */
    SchemaOperationResult dropFieldIndex(String modelCode, String fieldCode);

    // ==================== 表结构分析 ====================

    /**
     * 比较模型定义与实际表结构的差异
     * @param modelCode 模型编码
     * @return 差异分析结果
     */
    SchemaDiffResult compareModelWithTable(String modelCode);

    /**
     * 获取模型对应的表信息
     * @param modelCode 模型编码
     * @return 表信息
     */
    TableInfo getTableInfoByModel(String modelCode);

    /**
     * 验证模型定义的完整性
     * @param modelCode 模型编码
     * @return 验证结果
     */
    ModelValidationResult validateModel(String modelCode);

    // ==================== 批量操作 ====================

    /**
     * 批量同步多个模型
     * @param modelCodes 模型编码列表
     * @param syncOptions 同步选项
     * @return 批量操作结果
     */
    BatchSchemaOperationResult batchSyncModels(List<String> modelCodes, SchemaSyncOptions syncOptions);

    /**
     * 预览模型变更的DDL语句
     * @param modelCode 模型编码
     * @return DDL预览结果
     */
    DDLPreviewResult previewModelChanges(String modelCode);
}