package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.Dict;
import com.auraboot.framework.meta.entity.DictItem;
import com.auraboot.framework.meta.entity.payload.DataSourceItemBean;
import com.auraboot.framework.meta.mapper.DictItemMapper;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.service.DictVersionService;
import com.auraboot.framework.common.util.DateUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 字典版本策略服务实现类
 * 提供字典版本管理和数据加载的核心业务逻辑
 *
 * @author AuraBoot
 * @since 2024-01-01
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DictVersionServiceImpl implements DictVersionService {

    private final DictMapper dictMapper;
    private final DictItemMapper dictItemMapper;
    private final ObjectMapper objectMapper;

    // ==================== 版本策略管理 ====================

    @Override
    @Cacheable(value = "dictData", key = "T(com.auraboot.framework.meta.cache.MetaCacheKeyGenerator).getTenantContextSuffix() + ':' + #code + ':' + #versionStrategy + ':' + #pinnedVersion")
    public DictDataResult loadDictByStrategy(String code, 
                                           String versionStrategy, String pinnedVersion) {
        log.info("根据版本策略加载字典数据: code={}, strategy={}, pinnedVersion={}", 
                code, versionStrategy, pinnedVersion);
        
        DictDataResult result = new DictDataResult();
        result.setCode(code);
        result.setVersionStrategy(versionStrategy);
        result.setLoadTimestamp(System.currentTimeMillis());
        
        try {
            // 验证版本策略
            if (!validateVersionStrategy(versionStrategy, pinnedVersion)) {
                result.setSuccess(false);
                if ("pinned".equals(versionStrategy) && !StringUtils.hasText(pinnedVersion)) {
                    result.setErrorMessage("PINNED策略必须指定版本号");
                } else if (!StringUtils.hasText(versionStrategy) || 
                          (!versionStrategy.equals("latest") && !versionStrategy.equals("pinned"))) {
                    result.setErrorMessage("不支持的版本策略: " + versionStrategy);
                } else {
                    result.setErrorMessage("无效的版本策略配置");
                }
                return result;
            }
            
            Dict dict = null;
            
            // 根据版本策略获取字典
            if ("pinned".equals(versionStrategy)) {
                try {
                    Integer version = Integer.parseInt(pinnedVersion);
                    dict = dictMapper.findByCodeAndVersion(   code, version);
                    if (dict == null) {
                        result.setSuccess(false);
                        result.setErrorMessage("指定版本不存在: " + pinnedVersion);
                        return result;
                    }
                } catch (NumberFormatException e) {
                    result.setSuccess(false);
                    result.setErrorMessage("无效的版本号格式: " + pinnedVersion);
                    return result;
                }
            } else if ("latest".equals(versionStrategy)) {
                dict = dictMapper.findCurrentByCode(   code);
                if (dict == null) {
                    result.setSuccess(false);
                    result.setErrorMessage("字典不存在: " + code);
                    return result;
                }
            } else {
                result.setSuccess(false);
                result.setErrorMessage("不支持的版本策略: " + versionStrategy);
                return result;
            }
            
            // 检查字典状态
            if (!StatusConstants.ENABLED.equals(dict.getStatus()) && !StatusConstants.PUBLISHED.equals(dict.getStatus())) {
                result.setSuccess(false);
                result.setErrorMessage("字典未启用: " + code);
                return result;
            }
            
            // 加载字典数据
            DictDataResult dictResult = loadUnifiedDictData(dict);
            dictResult.setVersionStrategy(versionStrategy);
            return dictResult;
            
        } catch (Exception e) {
            log.error("加载字典数据失败: code={}", code, e);
            result.setSuccess(false);
            result.setErrorMessage("加载失败: " + e.getMessage());
            return result;
        }
    }

    @Override
    public List<DictDataResult> batchLoadDictByStrategy(List<DictLoadRequest> requests) {
        log.info("批量根据版本策略加载字典数据: count={}", requests.size());
        
        return requests.parallelStream()
                .map(request -> loadDictByStrategy(

                    request.getCode(), 
                    request.getVersionStrategy(), 
                    request.getPinnedVersion()
                ))
                .collect(Collectors.toList());
    }

    @Override
    public DictVersionInfo getDictVersionInfo(    String code) {
        log.info("获取字典版本信息: code={}", code);
        
        List<Dict> allVersions = dictMapper.findAllVersionsByCode(   code);
        if (allVersions.isEmpty()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "字典不存在: " + code);
        }
        
        DictVersionInfo versionInfo = new DictVersionInfo();
        versionInfo.setCode(code);
        versionInfo.setName(allVersions.get(0).getName());
        versionInfo.setTotalVersions(allVersions.size());
        
        // 查找当前版本和最新版本
        Dict currentVersion = allVersions.stream()
                .filter(Dict::isCurrentVersion)
                .findFirst()
                .orElse(null);
        
        Dict latestVersion = allVersions.stream()
                .max(Comparator.comparing(Dict::getVersion))
                .orElse(null);
        
        if (currentVersion != null) {
            versionInfo.setCurrentVersion(currentVersion.getVersion());
            versionInfo.setCurrentSemver(currentVersion.getSemver());
        }
        
        if (latestVersion != null) {
            versionInfo.setLatestVersion(latestVersion.getVersion());
            versionInfo.setLatestSemver(latestVersion.getSemver());
        }
        
        // 检查是否有未发布的版本
        boolean hasUnpublished = allVersions.stream()
                .anyMatch(dict -> StatusConstants.DRAFT.equals(dict.getStatus()));
        versionInfo.setHasUnpublishedVersions(hasUnpublished);
        
        // 构建版本历史
        List<DictVersionInfo.VersionHistoryItem> history = allVersions.stream()
                .map(dict -> {
                    DictVersionInfo.VersionHistoryItem item = new DictVersionInfo.VersionHistoryItem();
                    item.setVersion(dict.getVersion());
                    item.setSemver(dict.getSemver());
                    item.setIsCurrent(dict.isCurrentVersion());
                    item.setStatus(dict.getStatus());
                    item.setCreatedAt(DateUtil.toUtcLocalDateTime(dict.getCreatedAt()));
                    return item;
                })
                .collect(Collectors.toList());
        
        versionInfo.setVersionHistory(history);
        versionInfo.setCreatedAt(DateUtil.toUtcLocalDateTime(allVersions.get(allVersions.size() - 1).getCreatedAt()));
        versionInfo.setLastUpdatedAt(DateUtil.toUtcLocalDateTime(allVersions.get(0).getUpdatedAt()));
        
        return versionInfo;
    }

    @Override
    public List<String> getAvailableVersions(    String code) {
        List<Dict> allVersions = dictMapper.findAllVersionsByCode(   code);
        return allVersions.stream()
                .filter(dict -> !StatusConstants.DISABLED.equals(dict.getStatus()))
                .map(dict -> dict.getSemver() != null ? dict.getSemver() : dict.getVersion().toString())
                .collect(Collectors.toList());
    }

    // ==================== 小字典和大字典统一加载 ====================

    @Override
    public DictDataResult loadUnifiedDictData(Dict dict) {
        log.info("统一加载字典数据: code={}, type={}", dict.getCode(), dict.getDictType());
        
        try {
            // 所有字典类型统一从 ab_dict_item 表加载
            // DYNAMIC: 普通字典
            // TREE/CASCADE: 树形字典（支持 parent_value）
            // STATIC: 兼容旧数据，也从 dict_item 表加载
            
            String dictType = dict.getDictType();
            if ("tree".equals(dictType) || "cascade".equals(dictType)) {
                return loadTreeDictData(dict);
            } else {
                // DYNAMIC 或其他类型，统一使用动态加载
                return loadDynamicDictData(dict);
            }
        } catch (Exception e) {
            log.error("加载字典数据失败: code={}", dict.getCode(), e);
            DictDataResult result = new DictDataResult();
            result.setCode(dict.getCode());
            result.setName(dict.getName());
            result.setDictType(dict.getDictType());
            result.setVersion(dict.getSemver());
            result.setLoadTimestamp(System.currentTimeMillis());
            result.setSuccess(false);
            result.setErrorMessage("加载失败: " + e.getMessage());
            return result;
        }
    }

    @Override
    public DictDataResult loadStaticDictData(Dict dict) {
        log.info("加载STATIC字典数据: code={}", dict.getCode());
        
        DictDataResult result = new DictDataResult();
        result.setCode(dict.getCode());
        result.setName(dict.getName());
        result.setDictType(dict.getDictType());
        result.setVersion(dict.getSemver());
        result.setLoadTimestamp(System.currentTimeMillis());
        
        try {
            // 验证字典类型
            if (!"static".equalsIgnoreCase(dict.getDictType())) {
                log.error("字典类型不是STATIC: code={}, type={}", dict.getCode(), dict.getDictType());
                result.setSuccess(false);
                result.setErrorMessage("字典类型不是STATIC: " + dict.getDictType());
                return result;
            }
            
            List<DataSourceItemBean> sourceItems = dict.getItems();
            
            if (sourceItems == null || sourceItems.isEmpty()) {
                log.error("STATIC字典缺少items定义: code={}", dict.getCode());
                result.setSuccess(false);
                result.setErrorMessage("STATIC字典缺少items定义");
                return result;
            }
            
            // 转换为DictItemData格式
            List<DictDataResult.DictItemData> items = new ArrayList<>();
            Map<String, Object> itemMap = new HashMap<>();
            
            for (DataSourceItemBean sourceItem : sourceItems) {
                DictDataResult.DictItemData item = new DictDataResult.DictItemData();
                
                // 使用value作为主键,如果没有则使用key或code
                String itemValue = sourceItem.getValue() != null ? 
                    sourceItem.getValue().toString() : 
                    (sourceItem.getKey() != null ? sourceItem.getKey() : sourceItem.getCode());
                
                item.setValue(itemValue);
                item.setLabel(sourceItem.getLabel() != null ? sourceItem.getLabel() : sourceItem.getName());
                item.setSortOrder(sourceItem.getOrder() != null ? sourceItem.getOrder() : 0);
                item.setEnabled(sourceItem.getDisabled() == null || !sourceItem.getDisabled());
                
                // 如果有扩展属性,可以存储
                if (sourceItem.getExtra() != null) {
                    item.setExtension(sourceItem.getExtra());
                }
                
                items.add(item);
                itemMap.put(itemValue, item.getLabel());
            }
            
            result.setItems(items);
            result.setItemMap(itemMap);
            result.setSuccess(true);
            
            log.info("STATIC字典数据加载成功: code={}, itemCount={}", dict.getCode(), items.size());
            return result;
            
        } catch (Exception e) {
            log.error("加载STATIC字典数据失败: code={}", dict.getCode(), e);
            result.setSuccess(false);
            result.setErrorMessage("加载失败: " + e.getMessage());
            return result;
        }
    }

    @Override
    public DictDataResult loadDynamicDictData(Dict dict) {
        log.info("加载字典数据: code={}, type={}", dict.getCode(), dict.getDictType());
        
        DictDataResult result = new DictDataResult();
        result.setCode(dict.getCode());
        result.setName(dict.getName());
        result.setDictType(dict.getDictType());
        result.setVersion(dict.getSemver());
        result.setLoadTimestamp(System.currentTimeMillis());
        
        try {
            // 从 ab_dict_item 表查询字典项
            List<DictItem> dictItems = dictItemMapper.findByDictId(dict.getId());
            
            List<DictDataResult.DictItemData> items = new ArrayList<>();
            Map<String, Object> itemMap = new HashMap<>();
            
            for (DictItem dictItem : dictItems) {
                DictDataResult.DictItemData item = new DictDataResult.DictItemData();
                item.setValue(dictItem.getValue());
                item.setLabel(dictItem.getLabel());
                item.setSortOrder(dictItem.getSortNo());
                item.setParentValue(dictItem.getParentValue());
                item.setEnabled(StatusConstants.ENABLED.equals(dictItem.getStatus()));
                // Map extra (DB column) to extension (API response)
                if (dictItem.hasExtra()) {
                    item.setExtension(dictItem.getExtra());
                }

                items.add(item);
                itemMap.put(item.getValue(), item.getLabel());
            }

            result.setItems(items);
            result.setItemMap(itemMap);
            result.setSuccess(true);

            log.info("字典数据加载成功: code={}, itemCount={}", dict.getCode(), items.size());
            return result;
            
        } catch (Exception e) {
            log.error("加载字典数据失败: code={}", dict.getCode(), e);
            result.setSuccess(false);
            result.setErrorMessage("加载失败: " + e.getMessage());
            return result;
        }
    }

    /**
     * 加载树形字典数据（支持层级结构）
     * 
     * @param dict 字典实体
     * @return 字典数据结果
     */
    private DictDataResult loadTreeDictData(Dict dict) {
        log.info("加载树形字典数据: code={}", dict.getCode());
        
        // 树形字典也从 dict_item 表加载，只是支持 parent_value 字段
        return loadDynamicDictData(dict);
    }

    @Override
    public DictDataResult loadCascadeDictData(Dict dict, String parentValue) {
        log.info("加载级联字典数据: code={}, parentValue={}", dict.getCode(), parentValue);
        
        DictDataResult result = new DictDataResult();
        result.setCode(dict.getCode());
        result.setName(dict.getName());
        result.setDictType(dict.getDictType());
        result.setVersion(dict.getSemver());
        result.setLoadTimestamp(System.currentTimeMillis());
        
        try {
            // 查询字典项（支持级联过滤）
            List<DictItem> dictItems;
            if (StringUtils.hasText(parentValue)) {
                dictItems = dictItemMapper.findByDictIdAndParentValue(dict.getId(), parentValue);
            } else {
                dictItems = dictItemMapper.findTopLevelByDictId(dict.getId());
            }
            
            List<DictDataResult.DictItemData> items = new ArrayList<>();
            Map<String, Object> itemMap = new HashMap<>();
            
            for (DictItem dictItem : dictItems) {
                DictDataResult.DictItemData item = new DictDataResult.DictItemData();
                item.setValue(dictItem.getValue());
                item.setLabel(dictItem.getLabel());
                item.setSortOrder(dictItem.getSortNo());
                item.setParentValue(dictItem.getParentValue());
                item.setEnabled(StatusConstants.ENABLED.equals(dictItem.getStatus()));
                if (dictItem.hasExtra()) {
                    item.setExtension(dictItem.getExtra());
                }

                items.add(item);
                itemMap.put(item.getValue(), item.getLabel());
            }

            result.setItems(items);
            result.setItemMap(itemMap);
            result.setSuccess(true);
            
            log.info("级联字典数据加载成功: code={}, parentValue={}, itemCount={}", 
                    dict.getCode(), parentValue, items.size());
            return result;
            
        } catch (Exception e) {
            log.error("加载级联字典数据失败: code={}", dict.getCode(), e);
            result.setSuccess(false);
            result.setErrorMessage("加载失败: " + e.getMessage());
            return result;
        }
    }

    // ==================== 版本切换和管理 ====================

    @Override
    @CacheEvict(value = "dictData", allEntries = true)
    public boolean switchCurrentVersion(String code, Integer targetVersion) {
        log.info("切换字典当前版本: code={}, targetVersion={}", code, targetVersion);
        
        try {
            // 检查目标版本是否存在
            Dict targetDict = dictMapper.findByCodeAndVersion(   code, targetVersion);
            if (targetDict == null) {
                log.warn("目标版本不存在: code={}, version={}", code, targetVersion);
                return false;
            }
            
            // 清除当前版本标记
            dictMapper.clearCurrentFlag(   code);
            
            // 设置新的当前版本
            dictMapper.setCurrentVersion(targetDict.getId());
            
            log.info("字典版本切换成功: code={}, targetVersion={}", code, targetVersion);
            return true;
            
        } catch (Exception e) {
            log.error("切换字典版本失败: code={}, targetVersion={}", code, targetVersion, e);
            return false;
        }
    }

    @Override
    public Dict getCurrentVersion(    String code) {
        return dictMapper.findCurrentByCode(   code);
    }

    @Override
    public Dict getSpecificVersion(    String code, Integer version) {
        return dictMapper.findByCodeAndVersion(   code, version);
    }

    // ==================== 版本兼容性和验证 ====================

    @Override
    public boolean validateVersionStrategy(String versionStrategy, String pinnedVersion) {
        if (!StringUtils.hasText(versionStrategy)) {
            return false;
        }
        
        if ("pinned".equals(versionStrategy)) {
            if (!StringUtils.hasText(pinnedVersion)) {
                return false;
            }
            try {
                Integer.parseInt(pinnedVersion);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        } else if ("latest".equals(versionStrategy)) {
            return true;
        }
        
        return false;
    }

    @Override
    public boolean isVersionExists(    String code, Integer version) {
        Dict dict = dictMapper.findByCodeAndVersion(   code, version);
        return dict != null;
    }

    @Override
    public Map<String, Object> getVersionCompatibility(    String code, 
                                                      Integer fromVersion, Integer toVersion) {
        Map<String, Object> compatibility = new HashMap<>();
        
        Dict fromDict = dictMapper.findByCodeAndVersion(   code, fromVersion);
        Dict toDict = dictMapper.findByCodeAndVersion(   code, toVersion);
        
        if (fromDict == null || toDict == null) {
            compatibility.put("compatible", false);
            compatibility.put("reason", "版本不存在");
            return compatibility;
        }
        
        // 基本兼容性检查
        boolean compatible = fromDict.getDictType().equals(toDict.getDictType());
        compatibility.put("compatible", compatible);
        
        if (!compatible) {
            compatibility.put("reason", "字典类型不匹配");
        }
        
        compatibility.put("fromVersion", fromVersion);
        compatibility.put("toVersion", toVersion);
        compatibility.put("fromType", fromDict.getDictType());
        compatibility.put("toType", toDict.getDictType());
        
        return compatibility;
    }

    // ==================== 缓存管理 ====================

    @Override
    public void warmupDictCache(    List<String> codes) {
        log.info("预热字典缓存:  codes={}", codes != null ? codes.size() : "all");


        //todo fixme
        List<String> targetCodes = codes;
        if (targetCodes == null || targetCodes.isEmpty()) {
            // 获取所有字典编码
            List<Dict> allDicts = dictMapper.findCurrentByTenant(  );
            targetCodes = allDicts.stream()
                    .map(Dict::getCode)
                    .collect(Collectors.toList());
        }
        
        // 并行预热缓存
        targetCodes.parallelStream().forEach(code -> {
            try {
                loadDictByStrategy(   code, "latest", null);
            } catch (Exception e) {
                log.warn("预热字典缓存失败: code={}", code, e);
            }
        });
        
        log.info("字典缓存预热完成: count={}", targetCodes.size());
    }

    @Override
    @CacheEvict(value = "dictData", allEntries = true)
    public void clearDictCache(String code) {
        log.info("清除字典缓存: code={}", code);
    }

    @Override
    @CacheEvict(value = "dictData", allEntries = true)
    public void clearAllDictCache( ) {
        log.info("清除所有字典缓存: tenantId={}", MetaContext.getCurrentTenantId());
    }

    // ==================== 统计和监控 ====================

    @Override
    public Map<String, Long> getVersionStrategyStats( ) {
        Map<String, Long> stats = new HashMap<>();
        
        // TODO: 实现版本策略使用统计
        // 这里需要记录和统计版本策略的使用情况
        stats.put("latest", 0L);
        stats.put("pinned", 0L);
        
        return stats;
    }

    @Override
    public Map<String, Object> getDictLoadPerformanceStats( ) {
        Map<String, Object> stats = new HashMap<>();
        
        // TODO: 实现字典加载性能统计
        // 这里需要记录和统计字典加载的性能数据
        stats.put("avgLoadTime", 0L);
        stats.put("totalLoads", 0L);
        stats.put("cacheHitRate", 0.0);
        
        return stats;
    }
}
