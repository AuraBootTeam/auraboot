package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.*;
import java.util.List;
import java.util.Map;

/**
 * 动态数据服务
 * 职责：为前台页面提供基于模型的增删改查和列表操作
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
public interface DynamicDataService {

    // ==================== 基础CRUD操作 ====================

    /**
     * 分页查询数据
     * @param modelCode 模型编码
     * @param request 分页请求参数
     * @return 分页结果
     */
    PaginationResult<Map<String, Object>> list(String modelCode, DynamicQueryRequest request);

    /**
     * List data by executing a NamedQuery directly (for pages with dataSource.type = "namedQuery").
     * Converts DynamicQueryRequest conditions to NamedQuery format and delegates to NamedQueryService.
     *
     * @param queryCode the named query code
     * @param request   dynamic query request with filters, sort, pagination
     * @return paginated results
     */
    PaginationResult<Map<String, Object>> listByQueryCode(String queryCode, DynamicQueryRequest request);

    /**
     * 根据ID获取单条数据
     * @param modelCode 模型编码
     * @param recordId 记录ID
     * @return 数据记录
     */
    Map<String, Object> getById(String modelCode, String recordId);

    /**
     * 创建数据
     * @param modelCode 模型编码
     * @param data 数据内容
     * @return 创建的数据记录
     */
    Map<String, Object> create(String modelCode, Map<String, Object> data);

    /**
     * 更新数据
     * @param modelCode 模型编码
     * @param recordId 记录ID
     * @param data 更新的数据内容
     * @return 更新后的数据记录
     */
    Map<String, Object> update(String modelCode, String recordId, Map<String, Object> data);

    /**
     * 删除数据
     * @param modelCode 模型编码
     * @param recordId 记录ID
     */
    void delete(String modelCode, String recordId);

    // ==================== 批量操作 ====================

    /**
     * 批量创建数据
     * @param modelCode 模型编码
     * @param dataList 数据列表
     * @return 批量操作结果
     */
    DynamicBatchResponse batchCreate(String modelCode, List<Map<String, Object>> dataList);

    /**
     * 批量更新数据
     * @param modelCode 模型编码
     * @param dataList 数据列表
     * @return 批量操作结果
     */
    DynamicBatchResponse batchUpdate(String modelCode, List<Map<String, Object>> dataList);

    /**
     * 批量删除数据
     * @param modelCode 模型编码
     * @param recordIds 记录ID列表
     */
    void batchDelete(String modelCode, List<String> recordIds);

    // ==================== 高级查询 ====================

    /**
     * 执行自定义查询
     * @param modelCode 模型编码
     * @param queryName 查询名称
     * @param queryParams 查询参数
     * @return 查询结果
     */
    List<Map<String, Object>> executeCustomQuery(String modelCode, String queryName, Map<String, Object> queryParams);

    /**
     * 聚合查询
     * @param modelCode 模型编码
     * @param aggregateRequest 聚合请求
     * @return 聚合结果
     */
    Map<String, Object> aggregate(String modelCode, AggregateRequest aggregateRequest);

    /**
     * 获取统计信息
     * @param modelCode 模型编码
     * @param statsParams 统计参数
     * @return 统计结果
     */
    Map<String, Object> getStats(String modelCode, Map<String, Object> statsParams);

    // ==================== 关联数据操作 ====================

    /**
     * 获取关联数据
     * @param modelCode 模型编码
     * @param recordId 记录ID
     * @param relationName 关联名称
     * @param queryParams 查询参数
     * @return 关联数据列表
     */
    List<Map<String, Object>> getRelationData(String modelCode, String recordId, String relationName, Map<String, Object> queryParams);

    /**
     * 创建关联关系
     * @param modelCode 模型编码
     * @param recordId 记录ID
     * @param relationName 关联名称
     * @param targetRecordIds 目标记录ID列表
     * @return 操作结果
     */
    RelationOperationResult createRelations(String modelCode, String recordId, String relationName, List<String> targetRecordIds);

    /**
     * 删除关联关系
     * @param modelCode 模型编码
     * @param recordId 记录ID
     * @param relationName 关联名称
     * @param targetRecordIds 目标记录ID列表
     * @return 操作结果
     */
    RelationOperationResult removeRelations(String modelCode, String recordId, String relationName, List<String> targetRecordIds);

    // ==================== 数据验证 ====================

    /**
     * 验证数据
     * @param modelCode 模型编码
     * @param data 数据内容
     * @param validationContext 验证上下文
     * @return 验证结果
     */
    ValidationResult validate(String modelCode, Map<String, Object> data, ValidationContext validationContext);

    /**
     * 获取字段选项
     * @param modelCode 模型编码
     * @param fieldCode 字段编码
     * @param optionRequest 选项请求参数
     * @return 字段选项列表
     */
    List<FieldOption> getFieldOptions(String modelCode, String fieldCode, FieldOptionRequest optionRequest);

    // ==================== 导入导出 ====================

    /**
     * 导出数据
     * @param modelCode 模型编码
     * @param exportRequest 导出请求
     * @return 导出结果
     */
    ExportResult exportData(String modelCode, DataExportRequest exportRequest);

    /**
     * 导入数据
     * @param modelCode 模型编码
     * @param importRequest 导入请求
     * @return 导入结果
     */
    ImportResult importData(String modelCode, DataImportRequest importRequest);

    // ==================== 自定义操作 ====================

    /**
     * 执行自定义操作
     * @param modelCode 模型编码
     * @param actionName 操作名称
     * @param actionParams 操作参数
     * @return 操作结果
     */
    ActionExecutionResult executeCustomAction(String modelCode, String actionName, Map<String, Object> actionParams);

    // ==================== 联合保存操作 ====================

    /**
     * 联合保存主表和子表数据
     * 在单个事务中保存主表记录及其关联的子表记录
     *
     * @param modelCode 主表模型编码
     * @param request 联合保存请求，包含主表数据和子表数据
     * @return 联合保存结果，包含主表ID和各子表保存数量
     */
    JointSubTableSaveResponse saveWithRelations(String modelCode, JointSubTableSaveRequest request);
}