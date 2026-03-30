package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.exception.ValidationException;

import java.time.Instant;
import java.util.List;

/**
 * 页面Schema服务接口
 * 提供页面配置管理的核心业务功能，包括CRUD操作、版本管理和业务查询
 * 
 * @author AuraBoot Framework
 * @since 1.0.0
 */
public interface PageSchemaService {

    // ==================== CRUD 基础方法 ====================

    /**
     * 创建页面配置
     * 
     * @param request 创建请求参数
     * @return 创建的页面配置DTO
     * @throws ValidationException 当请求参数验证失败时抛出
     * @throws IllegalArgumentException 当业务规则验证失败时抛出
     */
    PageSchemaDTO create(PageSchemaCreateRequest request);

    /**
     * 根据PID更新页面配置
     * 
     * @param pid 页面配置的业务主键
     * @param request 更新请求参数
     * @return 更新后的页面配置DTO
     * @throws ValidationException 当请求参数验证失败时抛出
     * @throws IllegalArgumentException 当页面不存在或业务规则验证失败时抛出
     */
    PageSchemaDTO update(String pid, PageSchemaUpdateRequest request);

    /**
     * 根据PID删除页面配置（软删除）
     * 
     * @param pid 页面配置的业务主键
     * @throws IllegalArgumentException 当页面不存在时抛出
     */
    void delete(String pid);

    /**
     * 根据PID查询页面配置
     * 
     * @param pid 页面配置的业务主键
     * @return 页面配置DTO，如果不存在则返回null
     */
    PageSchemaDTO findByPid(String pid);

    // ==================== 业务查询方法 ====================

    /**
     * 根据页面名称查询页面配置
     * 
     * @param name 页面名称
     * @return 页面配置DTO，如果不存在则返回null
     */
    PageSchemaDTO findByName(String name);

    /**
     * Find page schemas by kind.
     *
     * @param kind page kind (list, form, detail, dashboard)
     * @return matching page schema DTOs
     */
    List<PageSchemaDTO> findByKind(String kind);

    /**
     * 查询指定分类的模板页面配置
     * 
     * @param templateCategory 模板分类（BUSINESS、SYSTEM等）
     * @return 模板页面配置DTO列表
     */
    List<PageSchemaDTO> findTemplateSchemas(String templateCategory);

    /**
     * 查询所有已发布的页面配置
     * 
     * @return 已发布的页面配置DTO列表
     */
    List<PageSchemaDTO> findPublishedSchemas();

    /**
     * 根据关键词搜索页面配置
     * 支持在页面名称、标题、描述中进行模糊搜索
     * 
     * @param keyword 搜索关键词
     * @return 匹配的页面配置DTO列表
     */
    List<PageSchemaDTO> searchByKeyword(String keyword);

    /**
     * Paginated query with optional filters (list view, lightweight DTO).
     *
     * @param kind page kind filter (optional)
     * @param isTemplate template filter (optional)
     * @param isPublished published filter (optional)
     * @param keyword search keyword (optional)
     * @param request pagination params
     * @return paginated result
     */
    PaginationResult<PageSchemaListDTO> findPageWithConditions(
            String kind,
            Boolean isTemplate,
            Boolean isPublished,
            String keyword,
            PaginationRequest request
    );

    // ==================== 版本管理方法 ====================

    /**
     * 发布页面配置
     * 将页面配置标记为已发布状态，并设置发布时间
     * 
     * @param pid 页面配置的业务主键
     * @return 发布后的页面配置DTO
     * @throws IllegalArgumentException 当页面不存在或已经发布时抛出
     */
    PageSchemaDTO publish(String pid);

    /**
     * 取消发布页面配置
     * 将页面配置标记为未发布状态
     * 
     * @param pid 页面配置的业务主键
     * @return 取消发布后的页面配置DTO
     * @throws IllegalArgumentException 当页面不存在或未发布时抛出
     */
    PageSchemaDTO unpublish(String pid);

    /**
     * 创建页面配置版本
     * 基于当前页面配置创建新版本
     * 
     * @param pid 页面配置的业务主键
     * @param reason 创建版本的原因说明
     * @return 新版本的页面配置DTO
     * @throws IllegalArgumentException 当页面不存在时抛出
     */
    PageSchemaDTO createVersion(String pid, String reason);

    /**
     * 获取页面配置的版本历史
     * 
     * @param pid 页面配置的业务主键
     * @return 版本历史列表，按创建时间倒序排列
     * @throws IllegalArgumentException 当页面不存在时抛出
     */
    List<PageSchemaDTO> getVersionHistory(String pid);

    // ==================== 统计和验证方法 ====================

    /**
     * 统计页面配置总数
     * 
     * @return 页面配置总数
     */
    long countTotal();

    /**
     * 统计已发布的页面配置数量
     * 
     * @return 已发布的页面配置数量
     */
    long countPublished();

    /**
     * 统计模板页面配置数量
     * 
     * @return 模板页面配置数量
     */
    long countTemplates();

    /**
     * 验证页面名称是否唯一
     * 
     * @param name 页面名称
     * @param excludePid 排除的页面PID（用于更新时的验证）
     * @return true表示名称唯一，false表示名称已存在
     */
    boolean isNameUnique(String name, String excludePid);

    /**
     * Validate the blocks list structure.
     *
     * @param blocks ordered list of page blocks
     * @return true if valid
     */
    boolean validateBlocks(Object blocks);

    // ==================== 统一控制器支持方法 ====================

    /**
     * 根据实体编码获取页面Schema配置
     * 用于统一控制器架构，支持根据实体编码动态获取对应的页面配置
     * 
     * @param entityCode 实体编码（如：store、user、order等）
     * @param schemaType Schema类型（list、form、view等）
     * @return 页面配置DTO，如果不存在则返回null
     */
    PageSchemaDTO findByEntityCode(String entityCode, String schemaType);

    /**
     * 根据页面键获取页面Schema配置
     * 用于统一控制器架构，支持根据页面键动态获取对应的页面配置
     *
     * @param pageKey 页面键（格式：entityCode-schemaType，如：store-list、store-form等）
     * @return 页面配置DTO，如果不存在则返回null
     */
    PageSchemaDTO findByPageKey(String pageKey);

    /**
     * 根据页面键获取页面Schema配置（包含草稿和已发布）
     * 用于插件导入等需要检查所有非删除页面的场景
     *
     * @param pageKey 页面键
     * @return 页面配置DTO（无论是否发布），如果不存在则返回null
     */
    PageSchemaDTO findAnyByPageKey(String pageKey);

    /**
     * 根据模型编码获取关联的页面配置列表
     *
     * @param modelCode 模型编码
     * @return 关联的页面配置DTO列表
     */
    List<PageSchemaDTO> findByModelCode(String modelCode);

    // ==================== Mobile Sync Support ====================

    /**
     * Get lightweight version metadata for schemas updated since a given timestamp.
     * Used by mobile clients to determine which schemas need re-fetching.
     *
     * @param since timestamp threshold (only schemas updated after this time are returned)
     * @return list of version metadata DTOs (lightweight, no blocks)
     */
    List<PageSchemaSyncVersionDTO> getVersionsSince(Instant since);

    /**
     * Batch fetch full page schemas by their page keys.
     * Used by mobile clients to fetch multiple schemas in a single request.
     *
     * @param pageKeys list of page keys to fetch
     * @return list of full PageSchemaDTO objects
     */
    List<PageSchemaDTO> batchGetByKeys(List<String> pageKeys);
}