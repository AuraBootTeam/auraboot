package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.LogSanitizer;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.Dict;
import com.auraboot.framework.meta.entity.DictItem;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.mapper.DictItemMapper;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.service.DictCascadeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 级联字典服务实现类
 * 提供级联字典的参数映射和动态查询功能
 *
 * @author AuraBoot
 * @since 2024-01-01
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DictCascadeServiceImpl implements DictCascadeService {

    private final DictMapper dictMapper;
    private final DictItemMapper dictItemMapper;
    private static final int BATCH_PAGE_SIZE = 100;

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }

    // ==================== 级联字典查询 ====================

    @Override
    @Cacheable(value = "cascadeDict", key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':' + #request.dictCode + ':' + #request.parentValue")
    public CascadeDictResult queryCascadeDict(CascadeDictRequest request) {
        ensureMetaContext("cascade dictionary query");
        log.info("查询级联字典数据: dictCode={}, parentValue={}", logSafe(request.getDictCode()), logSafe(request.getParentValue()));
        
        CascadeDictResult result = new CascadeDictResult(request.getDictCode(), request.getParentValue());
        
        try {
            // 验证字典是否存在
            Dict dict = dictMapper.findCurrentByCode(request.getDictCode());
            if (dict == null) {
                result.setFailure("字典不存在: " + request.getDictCode());
                return result;
            }
            
            if (!"cascade".equals(dict.getDictType())) {
                result.setFailure("字典类型不是级联字典: " + request.getDictCode());
                return result;
            }
            
            result.setDictName(dict.getName());
            
            // 执行查询
            List<DictItem> dictItems;
            if (request.getParentValue() != null) {
                dictItems = dictItemMapper.findByDictIdAndParentValue(dict.getId(), request.getParentValue());
            } else if (request.isRootQuery()) {
                dictItems = dictItemMapper.findTopLevelByDictId(dict.getId());
            } else {
                dictItems = dictItemMapper.findByDictId(dict.getId());
            }
            
            // 转换为DTO
            List<DictItemData> items = convertToDictItemData(dictItems);
            
            // 设置结果
            result.addItems(items);
            result.setSuccess();
            result.updateStatistics();
            
            log.info("级联字典数据查询成功: dictCode={}, itemCount={}", logSafe(request.getDictCode()), items.size());
            return result;
            
        } catch (Exception e) {
            log.error("查询级联字典数据失败: dictCode={}", logSafe(request.getDictCode()), e);
            result.setFailure("查询失败: " + e.getMessage());
            return result;
        }
    }

    @Override
    public List<DictItemData> getCascadeChildren(    
                                               String dictCode, String parentValue) {
        ensureMetaContext("cascade dictionary children query");
        log.info("获取级联字典子项: dictCode={}, parentValue={}", logSafe(dictCode), logSafe(parentValue));
        
        CascadeDictRequest request = new CascadeDictRequest(   dictCode, parentValue);
        CascadeDictResult result = queryCascadeDict(request);
        
        return result.getSuccess() ? result.getItems() : new ArrayList<>();
    }

    @Override
    public List<DictItemData> getCascadeRoots(    String dictCode) {
        ensureMetaContext("cascade dictionary root query");
        log.info("获取级联字典根项: dictCode={}", logSafe(dictCode));
        
        return getCascadeChildren(   dictCode, null);
    }

    @Override
    @Cacheable(value = "cascadeTree", key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':' + #dictCode")
    public DictTreeNode buildCascadeTree(String dictCode) {
        ensureMetaContext("cascade dictionary tree build");
        log.info("构建级联字典树: dictCode={}", logSafe(dictCode));
        
        return buildCascadeTree(   dictCode, null);
    }

    @Override
    @Cacheable(value = "cascadeTree", key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':' + #dictCode + ':' + #rootValue")
    public DictTreeNode buildCascadeTree(String dictCode, String rootValue) {
        ensureMetaContext("cascade dictionary tree build");
        log.info("构建级联字典树: dictCode={}, rootValue={}", logSafe(dictCode), logSafe(rootValue));
        
        try {
            // 获取所有字典项
            CascadeDictRequest request = new CascadeDictRequest(   dictCode);
            request.setIncludeDisabled(false);
            CascadeDictResult result = queryCascadeDict(request);
            
            if (!result.getSuccess()) {
                log.warn("获取字典项失败: {}", logSafe(result.getErrorMessage()));
                return new DictTreeNode();
            }
            
            List<DictItemData> allItems = result.getItems();
            if (allItems.isEmpty()) {
                return new DictTreeNode();
            }
            
            // 构建节点映射
            Map<String, DictTreeNode> nodeMap = new HashMap<>();
            Map<String, List<DictTreeNode>> childrenMap = new HashMap<>();
            
            // 创建所有节点
            for (DictItemData item : allItems) {
                DictTreeNode node = createTreeNode(item);
                nodeMap.put(item.getValue(), node);
                
                String parentKey = item.getParentValue() != null ? item.getParentValue() : "root";
                childrenMap.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(node);
            }
            
            // 构建父子关系
            for (DictItemData item : allItems) {
                DictTreeNode node = nodeMap.get(item.getValue());
                List<DictTreeNode> children = childrenMap.get(item.getValue());
                if (children != null) {
                    for (DictTreeNode child : children) {
                        node.addChild(child);
                    }
                }
            }
            
            // 查找根节点
            DictTreeNode root;
            if (StringUtils.hasText(rootValue)) {
                root = nodeMap.get(rootValue);
                if (root == null) {
                    log.warn("指定的根节点不存在: {}", logSafe(rootValue));
                    return new DictTreeNode();
                }
            } else {
                // 查找第一个根节点
                List<DictTreeNode> rootNodes = childrenMap.get("root");
                if (rootNodes == null || rootNodes.isEmpty()) {
                    log.warn("没有找到根节点");
                    return new DictTreeNode();
                }
                
                if (rootNodes.size() == 1) {
                    root = rootNodes.get(0);
                } else {
                    // 创建虚拟根节点
                    root = new DictTreeNode("root", "根节点");
                    for (DictTreeNode rootNode : rootNodes) {
                        root.addChild(rootNode);
                    }
                }
            }
            
            // 排序所有节点的子节点
            root.sortChildren();
            
            log.info("级联字典树构建成功: dictCode={}, nodeCount={}", logSafe(dictCode), nodeMap.size());
            return root;
            
        } catch (Exception e) {
            log.error("构建级联字典树失败: dictCode={}", logSafe(dictCode), e);
            return new DictTreeNode();
        }
    }

    // ==================== 参数映射和动态查询 ====================

    @Override
    public List<DictItemData> queryByParams(    
                                           String dictCode, Map<String, String> cascadeParams) {
        ensureMetaContext("cascade dictionary parameter query");
        log.info("根据参数映射查询级联字典: dictCode={}, params={}", logSafe(dictCode), logSafe(cascadeParams));
        
        CascadeDictRequest request = new CascadeDictRequest(   dictCode);
        request.setCascadeParams(cascadeParams);
        
        return queryByComposite(request);
    }

    @Override
    public List<DictItemData> queryByFilters(    
                                            String dictCode, Map<String, Object> filters) {
        ensureMetaContext("cascade dictionary filter query");
        log.info("动态查询级联字典: dictCode={}, filters={}", logSafe(dictCode), logSafe(filters));
        
        CascadeDictRequest request = new CascadeDictRequest(   dictCode);
        request.setFilters(filters);
        
        return queryByComposite(request);
    }

    @Override
    public List<DictItemData> queryByComposite(CascadeDictRequest request) {
        ensureMetaContext("cascade dictionary composite query");
        log.info("复合查询级联字典: dictCode={}", logSafe(request.getDictCode()));
        
        CascadeDictResult result = queryCascadeDict(request);
        return result.getSuccess() ? result.getItems() : new ArrayList<>();
    }

    // ==================== 层级结构管理 ====================

    @Override
    public Map<String, Object> getCascadeStructure(    String dictCode) {
        ensureMetaContext("cascade dictionary structure query");
        log.info("获取字典层级结构信息: dictCode={}", logSafe(dictCode));
        
        Map<String, Object> structure = new HashMap<>();
        
        try {
            CascadeDictRequest request = new CascadeDictRequest(   dictCode);
            CascadeDictResult result = queryCascadeDict(request);
            
            if (!result.getSuccess()) {
                structure.put("error", result.getErrorMessage());
                return structure;
            }
            
            structure.put("dictCode", dictCode);
            structure.put("dictName", result.getDictName());
            structure.put("totalCount", result.getTotalCount());
            structure.put("maxLevel", result.getMaxLevel());
            structure.put("levelMap", result.getLevelMap());
            structure.put("parentChildMap", result.getParentChildMap());
            
            // 统计每层的数量
            Map<Integer, Integer> levelCounts = new HashMap<>();
            for (Map.Entry<Integer, List<DictItemData>> entry : result.getLevelMap().entrySet()) {
                levelCounts.put(entry.getKey(), entry.getValue().size());
            }
            structure.put("levelCounts", levelCounts);
            
        } catch (Exception e) {
            log.error("获取字典层级结构信息失败: dictCode={}", logSafe(dictCode), e);
            structure.put("error", "获取失败: " + e.getMessage());
        }
        
        return structure;
    }

    @Override
    public List<DictItemData> getItemsByLevel(    
                                             String dictCode, Integer level) {
        ensureMetaContext("cascade dictionary level query");
        log.info("获取指定层级的字典项: dictCode={}, level={}", logSafe(dictCode), level);
        
        CascadeDictRequest request = new CascadeDictRequest(   dictCode);
        CascadeDictResult result = queryCascadeDict(request);
        
        if (!result.getSuccess()) {
            return new ArrayList<>();
        }
        
        return result.getItemsByLevel(level);
    }

    @Override
    public Integer getMaxLevel(    String dictCode) {
        ensureMetaContext("cascade dictionary max level query");
        log.info("获取字典最大层级: dictCode={}", logSafe(dictCode));
        
        Map<String, Object> structure = getCascadeStructure(   dictCode);
        return (Integer) structure.getOrDefault("maxLevel", 0);
    }

    @Override
    public List<DictItemData> getNodePath(    
                                         String dictCode, String nodeValue) {
        ensureMetaContext("cascade dictionary node path query");
        log.info("获取节点完整路径: dictCode={}, nodeValue={}", logSafe(dictCode), logSafe(nodeValue));
        
        List<DictItemData> path = new ArrayList<>();
        
        try {
            // 获取所有字典项
            CascadeDictRequest request = new CascadeDictRequest(   dictCode);
            CascadeDictResult result = queryCascadeDict(request);
            
            if (!result.getSuccess()) {
                return path;
            }
            
            // 构建值到项的映射
            Map<String, DictItemData> itemMap = result.getItems().stream()
                    .collect(Collectors.toMap(DictItemData::getValue, item -> item));
            
            // 从目标节点向上追溯
            String currentValue = nodeValue;
            while (currentValue != null) {
                DictItemData item = itemMap.get(currentValue);
                if (item == null) {
                    break;
                }
                
                path.add(0, item); // 插入到开头，保持从根到叶的顺序
                currentValue = item.getParentValue();
            }
            
        } catch (Exception e) {
            log.error("获取节点完整路径失败: dictCode={}, nodeValue={}", logSafe(dictCode), logSafe(nodeValue), e);
        }
        
        return path;
    }

    // ==================== 缓存策略 ====================

    @Override
    public void warmupCascadeCache(    String dictCode) {
        ensureMetaContext("cascade dictionary cache warmup");
        log.info("预热级联字典缓存: dictCode={}", logSafe(dictCode));
        
        try {
            // 预热基础查询缓存
            CascadeDictRequest request = new CascadeDictRequest(   dictCode);
            queryCascadeDict(request);
            
            // 预热树结构缓存
            buildCascadeTree(   dictCode);
            
            log.info("级联字典缓存预热完成: dictCode={}", logSafe(dictCode));
        } catch (Exception e) {
            log.warn("预热级联字典缓存失败: dictCode={}", logSafe(dictCode), e);
        }
    }

    @Override
    @CacheEvict(value = {"cascadeDict", "cascadeTree"}, allEntries = true)
    public void clearCascadeCache(String dictCode) {
        ensureMetaContext("cascade dictionary cache clear");
        log.info("清除级联字典缓存: dictCode={}", logSafe(dictCode));
    }

    @Override
    public void refreshCascadeCache(    String dictCode) {
        ensureMetaContext("cascade dictionary cache refresh");
        log.info("刷新级联字典缓存: dictCode={}", logSafe(dictCode));
        
        // 先清除缓存
        clearCascadeCache(   dictCode);
        
        // 再预热缓存
        warmupCascadeCache(   dictCode);
    }

    // ==================== 验证和检查 ====================

    @Override
    public DictValidationResult validateCascadeConfig(    String dictCode) {
        ensureMetaContext("cascade dictionary config validation");
        log.info("验证级联字典配置: dictCode={}", logSafe(dictCode));
        
        DictValidationResult result = new DictValidationResult();
        
        try {
            // 检查字典是否存在
            Dict dict = dictMapper.findCurrentByCode(dictCode);
            if (dict == null) {
                result.addError("字典不存在: " + dictCode);
                return result;
            }
            
            if (!"cascade".equals(dict.getDictType())) {
                result.addError("字典类型不是级联字典: " + dictCode);
                return result;
            }
            
            // 检查循环引用
            if (hasCircularReference(   dictCode)) {
                result.addError("存在循环引用");
            }
            
            // 检查数据完整性
            Map<String, Object> integrity = checkCascadeIntegrity(   dictCode);
            if (!(Boolean) integrity.getOrDefault("valid", true)) {
                result.addError("数据完整性检查失败: " + integrity.get("message"));
            }
            
        } catch (Exception e) {
            log.error("验证级联字典配置失败: dictCode={}", logSafe(dictCode), e);
            result.addError("验证失败: " + e.getMessage());
        }
        
        return result;
    }

    @Override
    public Map<String, Object> checkCascadeIntegrity(    String dictCode) {
        ensureMetaContext("cascade dictionary integrity check");
        log.info("检查级联关系完整性: dictCode={}", logSafe(dictCode));
        
        Map<String, Object> result = new HashMap<>();
        result.put("valid", true);
        
        try {
            CascadeDictRequest request = new CascadeDictRequest(   dictCode);
            CascadeDictResult cascadeResult = queryCascadeDict(request);
            
            if (!cascadeResult.getSuccess()) {
                result.put("valid", false);
                result.put("message", cascadeResult.getErrorMessage());
                return result;
            }
            
            List<DictItemData> items = cascadeResult.getItems();
            Set<String> allValues = items.stream().map(DictItemData::getValue).collect(Collectors.toSet());
            List<String> orphanItems = new ArrayList<>();
            
            // 检查孤儿节点
            for (DictItemData item : items) {
                if (item.getParentValue() != null && !allValues.contains(item.getParentValue())) {
                    orphanItems.add(item.getValue());
                }
            }
            
            if (!orphanItems.isEmpty()) {
                result.put("valid", false);
                result.put("message", "存在孤儿节点");
                result.put("orphanItems", orphanItems);
            }
            
            result.put("totalItems", items.size());
            result.put("orphanCount", orphanItems.size());
            
        } catch (Exception e) {
            log.error("检查级联关系完整性失败: dictCode={}", logSafe(dictCode), e);
            result.put("valid", false);
            result.put("message", "检查失败: " + e.getMessage());
        }
        
        return result;
    }

    @Override
    public boolean hasCircularReference(    String dictCode) {
        ensureMetaContext("cascade dictionary circular reference check");
        log.info("检查循环引用: dictCode={}", logSafe(dictCode));
        
        try {
            CascadeDictRequest request = new CascadeDictRequest(   dictCode);
            CascadeDictResult result = queryCascadeDict(request);
            
            if (!result.getSuccess()) {
                return false;
            }
            
            List<DictItemData> items = result.getItems();
            Map<String, String> parentMap = items.stream()
                    .filter(item -> item.getParentValue() != null)
                    .collect(Collectors.toMap(DictItemData::getValue, DictItemData::getParentValue));
            
            // 使用深度优先搜索检测环
            Set<String> visited = new HashSet<>();
            Set<String> recursionStack = new HashSet<>();
            
            for (String value : parentMap.keySet()) {
                if (hasCycleDFS(value, parentMap, visited, recursionStack)) {
                    return true;
                }
            }
            
            return false;
            
        } catch (Exception e) {
            log.error("检查循环引用失败: dictCode={}", logSafe(dictCode), e);
            return false;
        }
    }

    // ==================== 批量操作 ====================

    @Override
    public List<CascadeDictResult> batchQueryCascadeDict(List<CascadeDictRequest> requests) {
        ensureMetaContext("cascade dictionary batch query");
        if (requests == null || requests.isEmpty()) {
            return Collections.emptyList();
        }

        log.info("批量查询级联字典: count={}", requests.size());
        if (requests.size() > BATCH_PAGE_SIZE) {
            log.warn("批量请求过大，按批次处理: count={}, batchSize={}", requests.size(), BATCH_PAGE_SIZE);
        }

        List<CascadeDictResult> results = new ArrayList<>(requests.size());
        for (int start = 0; start < requests.size(); start += BATCH_PAGE_SIZE) {
            int end = Math.min(start + BATCH_PAGE_SIZE, requests.size());
            List<CascadeDictRequest> batch = requests.subList(start, end);
            for (CascadeDictRequest request : batch) {
                results.add(queryCascadeDict(request));
            }
        }

        return results;
    }

    @Override
    public Map<String, DictTreeNode> batchBuildCascadeTree(    
                                                          List<String> dictCodes) {
        ensureMetaContext("cascade dictionary batch tree build");
        if (dictCodes == null || dictCodes.isEmpty()) {
            return Collections.emptyMap();
        }

        log.info("批量构建级联字典树: count={}", dictCodes.size());
        if (dictCodes.size() > BATCH_PAGE_SIZE) {
            log.warn("批量请求过大，按批次处理: count={}, batchSize={}", dictCodes.size(), BATCH_PAGE_SIZE);
        }

        Map<String, DictTreeNode> result = new LinkedHashMap<>();
        for (int start = 0; start < dictCodes.size(); start += BATCH_PAGE_SIZE) {
            int end = Math.min(start + BATCH_PAGE_SIZE, dictCodes.size());
            List<String> batch = dictCodes.subList(start, end);
            for (String dictCode : batch) {
                result.put(dictCode, buildCascadeTree(   dictCode));
            }
        }

        return result;
    }

    // ==================== 统计和监控 ====================

    @Override
    public Map<String, Object> getCascadeStatistics(    String dictCode) {
        ensureMetaContext("cascade dictionary statistics");
        log.info("获取级联字典统计信息: dictCode={}", logSafe(dictCode));
        
        Map<String, Object> statistics = new HashMap<>();
        
        try {
            Map<String, Object> structure = getCascadeStructure(   dictCode);
            statistics.putAll(structure);
            
            // 添加额外的统计信息
            statistics.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            log.error("获取级联字典统计信息失败: dictCode={}", logSafe(dictCode), e);
            statistics.put("error", "获取失败: " + e.getMessage());
        }
        
        return statistics;
    }

    @Override
    public Map<String, Object> getCascadePerformanceMetrics(    String dictCode) {
        ensureMetaContext("cascade dictionary performance metrics");
        log.info("获取级联字典性能指标: dictCode={}", logSafe(dictCode));
        
        Map<String, Object> metrics = new HashMap<>();
        
        try {
            long startTime = System.currentTimeMillis();
            
            // 测试查询性能
            CascadeDictRequest request = new CascadeDictRequest(   dictCode);
            CascadeDictResult result = queryCascadeDict(request);
            
            long queryTime = System.currentTimeMillis() - startTime;
            
            metrics.put("queryTime", queryTime);
            metrics.put("itemCount", result.getItemCount());
            metrics.put("success", result.getSuccess());
            
            if (result.getSuccess()) {
                // 测试树构建性能
                startTime = System.currentTimeMillis();
                buildCascadeTree(   dictCode);
                long treeTime = System.currentTimeMillis() - startTime;
                
                metrics.put("treeTime", treeTime);
                metrics.put("totalTime", queryTime + treeTime);
            }
            
        } catch (Exception e) {
            log.error("获取级联字典性能指标失败: dictCode={}", logSafe(dictCode), e);
            metrics.put("error", "获取失败: " + e.getMessage());
        }
        
        return metrics;
    }

    // ==================== 私有辅助方法 ====================

    private void ensureMetaContext(String operation) {

    }

    /**
     * 转换为字典项数据
     */
    private List<DictItemData> convertToDictItemData(List<DictItem> dictItems) {
        return dictItems.stream().map(item -> {
            DictItemData data = new DictItemData();
            data.setValue(item.getValue());
            data.setLabel(item.getLabel());
            data.setParentValue(item.getParentValue());
            data.setSortOrder(item.getSortNo());
            data.setEnabled(StatusConstants.ENABLED.equals(item.getStatus()));
            return data;
        }).collect(Collectors.toList());
    }

    /**
     * 创建树节点
     */
    private DictTreeNode createTreeNode(DictItemData item) {
        DictTreeNode node = new DictTreeNode(item.getValue(), item.getLabel(), item.getParentValue());
        node.setSortOrder(item.getSortOrder());
        node.setDisabled(!item.getEnabled());
        return node;
    }

    /**
     * 深度优先搜索检测环
     */
    private boolean hasCycleDFS(String node, Map<String, String> parentMap, 
                               Set<String> visited, Set<String> recursionStack) {
        if (recursionStack.contains(node)) {
            return true; // 发现环
        }
        
        if (visited.contains(node)) {
            return false; // 已经访问过，没有环
        }
        
        visited.add(node);
        recursionStack.add(node);
        
        String parent = parentMap.get(node);
        if (parent != null && hasCycleDFS(parent, parentMap, visited, recursionStack)) {
            return true;
        }
        
        recursionStack.remove(node);
        return false;
    }
}
