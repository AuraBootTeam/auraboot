package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.*;

import java.util.List;
import java.util.Map;

/**
 * Cascade dictionary service.
 * All methods rely on MetaContext for tenant resolution.
 */
public interface DictCascadeService {

    // ==================== 级联字典查询 ====================

    /**
     * Query cascade dictionary data.
     *
     * @param request cascade dictionary request
     * @return cascade dictionary result
     */
    CascadeDictResult queryCascadeDict(CascadeDictRequest request);

    /**
     * Get children items for a cascade dictionary.
     *
     * @param dictCode dictionary code
     * @param parentValue parent value
     * @return children items
     */
    List<DictItemData> getCascadeChildren(String dictCode, String parentValue);

    /**
     * Get root items for a cascade dictionary.
     *
     * @param dictCode dictionary code
     * @return root items
     */
    List<DictItemData> getCascadeRoots(String dictCode);

    /**
     * Build cascade dictionary tree.
     *
     * @param dictCode dictionary code
     * @return dictionary tree
     */
    DictTreeNode buildCascadeTree(String dictCode);

    /**
     * Build cascade dictionary tree with specified root.
     *
     * @param dictCode dictionary code
     * @param rootValue root value
     * @return dictionary tree
     */
    DictTreeNode buildCascadeTree(String dictCode, String rootValue);

    // ==================== 参数映射和动态查询 ====================

    /**
     * Query cascade dictionary by mapped parameters.
     *
     * @param dictCode dictionary code
     * @param cascadeParams cascade parameter map
     * @return dictionary items
     */
    List<DictItemData> queryByParams(String dictCode, Map<String, String> cascadeParams);

    /**
     * Query cascade dictionary by filters.
     *
     * @param dictCode dictionary code
     * @param filters filter conditions
     * @return dictionary items
     */
    List<DictItemData> queryByFilters(String dictCode, Map<String, Object> filters);

    /**
     * Composite query for cascade dictionary.
     *
     * @param request cascade dictionary request
     * @return dictionary items
     */
    List<DictItemData> queryByComposite(CascadeDictRequest request);

    // ==================== 层级结构管理 ====================

    /**
     * Get cascade structure information.
     *
     * @param dictCode dictionary code
     * @return structure info
     */
    Map<String, Object> getCascadeStructure(String dictCode);

    /**
     * Get items by level.
     *
     * @param dictCode dictionary code
     * @param level level index (start from 0)
     * @return dictionary items
     */
    List<DictItemData> getItemsByLevel(String dictCode, Integer level);

    /**
     * Get max level of a cascade dictionary.
     *
     * @param dictCode dictionary code
     * @return max level
     */
    Integer getMaxLevel(String dictCode);

    /**
     * Get full path for a node.
     *
     * @param dictCode dictionary code
     * @param nodeValue node value
     * @return node path
     */
    List<DictItemData> getNodePath(String dictCode, String nodeValue);

    // ==================== 缓存策略 ====================

    /**
     * Warm up cascade dictionary cache.
     *
     * @param dictCode dictionary code
     */
    void warmupCascadeCache(String dictCode);

    /**
     * Clear cascade dictionary cache.
     *
     * @param dictCode dictionary code
     */
    void clearCascadeCache(String dictCode);

    /**
     * Refresh cascade dictionary cache.
     *
     * @param dictCode dictionary code
     */
    void refreshCascadeCache(String dictCode);

    // ==================== 验证和检查 ====================

    /**
     * Validate cascade dictionary config.
     *
     * @param dictCode dictionary code
     * @return validation result
     */
    DictValidationResult validateCascadeConfig(String dictCode);

    /**
     * Check cascade integrity.
     *
     * @param dictCode dictionary code
     * @return check result
     */
    Map<String, Object> checkCascadeIntegrity(String dictCode);

    /**
     * Check whether circular references exist.
     *
     * @param dictCode dictionary code
     * @return true when circular reference exists
     */
    boolean hasCircularReference(String dictCode);

    // ==================== 批量操作 ====================

    /**
     * Batch query cascade dictionaries.
     *
     * @param requests request list
     * @return result list
     */
    List<CascadeDictResult> batchQueryCascadeDict(List<CascadeDictRequest> requests);

    /**
     * Batch build cascade dictionary trees.
     *
     * @param dictCodes dictionary codes
     * @return dictionary tree map
     */
    Map<String, DictTreeNode> batchBuildCascadeTree(List<String> dictCodes);

    // ==================== 统计和监控 ====================

    /**
     * Get cascade dictionary statistics.
     *
     * @param dictCode dictionary code
     * @return statistics
     */
    Map<String, Object> getCascadeStatistics(String dictCode);

    /**
     * Get cascade dictionary performance metrics.
     *
     * @param dictCode dictionary code
     * @return performance metrics
     */
    Map<String, Object> getCascadePerformanceMetrics(String dictCode);
}
