package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.ValidationException;

import com.auraboot.framework.application.tenant.MetaContext;

import com.auraboot.framework.meta.constant.Status;
import com.auraboot.framework.meta.converter.DictConverter;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.entity.Dict;
import com.auraboot.framework.meta.entity.DictItem;
import com.auraboot.framework.meta.mapper.DictItemMapper;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.service.DictCascadeService;
import com.auraboot.framework.meta.service.DictService;
import com.auraboot.framework.meta.service.DictVersionService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 字典管理服务实现类
 *
 * 使用MetaContext进行租户上下文管理
 *
 * @author AuraBoot Team
 * @since 2.1.3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictServiceImpl implements DictService {

    private final DictMapper dictMapper;
    private final DictItemMapper dictItemMapper;
    private final DictCascadeService dictCascadeService;
    private final DictVersionService dictVersionService;
    private final DictConverter dictConverter;


    private final ApplicationEventPublisher eventPublisher;  // ✅ 新增: 用于发布 Release 事件



    // ==================== 基础CRUD操作 ====================

    @Override
    @Transactional
    public DictDTO create(DictCreateRequest request) {
        if (request == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "创建请求不能为空");
        }
        
        log.info("创建字典: {}", request.getCode());

        return createDirectly(request);

    }

    
    /**
     * 直接创建字典(开发阶段保留)
     */
    private DictDTO createDirectly(DictCreateRequest request) {
        log.info("直接创建字典(非Git-First): {}", request.getCode());

        // 业务验证
        validateCreateRequest(request);
        validateCodeUnique(request.getCode(), null);

        // 转换实体并设置默认值
        Dict dict = dictConverter.toEntity(request);
        dict.setPid(UniqueIdGenerator.generate());
        dict.setTenantId(MetaContext.getCurrentTenantId());
        dict.setStatus(Status.PUBLISHED.getCode()); // Use unified status
        dict.setCreatedAt(Instant.now());
        dict.setUpdatedAt(Instant.now());

        // Set plugin_pid if provided
        if (org.springframework.util.StringUtils.hasText(request.getPluginPid())) {
            dict.setPluginPid(request.getPluginPid());
        }

        // 映射字典类型：前端 -> 后端
        // SIMPLE -> STATIC, TREE -> DYNAMIC, CASCADE -> CASCADE
        String backendDictType = mapDictType(request.getDictType());
        dict.setDictType(backendDictType);

        // 保存到数据库
        dictMapper.insert(dict);

        // 处理字典项（如果有）
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            saveDictItems(dict.getId(), request.getItems());
        }

        log.info("字典创建成功: {} (租户: {}), 字典项数量: {}", 
                dict.getPid(), MetaContext.getCurrentTenantId(), 
                request.getItems() != null ? request.getItems().size() : 0);
        return dictConverter.toDTO(dict);
    }

    /**
     * 映射前端字典类型到后端字典类型
     * SIMPLE -> DYNAMIC (普通字典)
     * TREE -> TREE (树形字典，包含 CASCADE)
     */
    private String mapDictType(String frontendType) {
        if (frontendType == null) {
            return "dynamic";
        }
        return switch (frontendType) {
            case "simple" -> "dynamic";
            case "tree", "cascade" -> "tree";
            default -> "dynamic";
        };
    }

    /**
     * Map frontend dict type to list of backend dict types for query.
     */
    private List<String> mapDictTypeToBackendList(String frontendType) {
        if (frontendType == null || frontendType.isEmpty()) {
            return null;
        }
        return switch (frontendType) {
            case "simple" -> List.of("dynamic", "static");
            case "tree" -> List.of("tree");
            case "cascade" -> List.of("cascade");
            default -> null;
        };
    }

    /**
     * 保存字典项到 ab_dict_item 表
     * 所有字典类型统一使用此方法保存字典项
     */
    private void saveDictItems(Long dictId, List<DictCreateRequest.DictItemCreateRequest> itemRequests) {
        if (itemRequests == null || itemRequests.isEmpty()) {
            return;
        }

        log.info("保存字典项: dictId={}, count={}", dictId, itemRequests.size());

        for (DictCreateRequest.DictItemCreateRequest itemRequest : itemRequests) {
            DictItem item = new DictItem();
            item.setPid(UniqueIdGenerator.generate());
            item.setTenantId(MetaContext.getCurrentTenantId());
            item.setDictId(dictId);
            item.setValue(itemRequest.getValue());
            item.setLabel(itemRequest.getLabel());
            item.setSortNo(itemRequest.getSortOrder() != null ? itemRequest.getSortOrder() : 0);
            item.setParentValue(itemRequest.getParentValue());
            item.setStatus(Boolean.TRUE.equals(itemRequest.getDisabled()) ? "disabled" : "enabled");
            item.setExtra(itemRequest.getExtension());
            item.setCreatedAt(Instant.now());
            item.setUpdatedAt(Instant.now());

            dictItemMapper.insert(item);
        }

        log.info("字典项保存完成: dictId={}, count={}", dictId, itemRequests.size());
    }

    @Override
    @Transactional
    public DictDTO update(String pid, DictUpdateRequest request) {
        log.info("更新字典: {}", pid);

        // 验证租户上下文
          

        // 查找现有记录（带租户上下文验证）
        Dict existingDict = findEntityByPid(pid);

        // 业务验证
        validateUpdateRequest(request);

        return updateDirectly(existingDict, request);
    }

    /**
     * 直接更新字典(开发阶段保留)
     */
    private DictDTO updateDirectly(Dict existingDict, DictUpdateRequest request) {
        log.info("直接更新字典(非Git-First): {}", existingDict.getCode());
        
        // 更新实体
        dictConverter.updateEntity(existingDict, request);
        existingDict.setUpdatedAt(Instant.now());
        
        // 保存更新
        dictMapper.updateById(existingDict);
        
        log.info("字典更新成功: {}", existingDict.getPid());
        return dictConverter.toDTO(existingDict);
    }

    @Override
    @Transactional
    public void delete(String pid) {
        log.info("删除字典: {}", pid);

        // 验证租户上下文
          

        Dict dict = findEntityByPid(pid);

        // 检查是否可以删除
        validateCanDelete(dict);

        deleteDirectly(dict);

    }
    

    
    /**
     * 直接删除字典(开发阶段保留)
     */
    private void deleteDirectly(Dict dict) {
        log.info("直接删除字典(非Git-First): {}", dict.getCode());
        
        // Soft delete by setting status to DISABLED
        dict.setStatus(Status.DISABLED.getCode());
        dict.setUpdatedAt(Instant.now());
        dictMapper.updateById(dict);
        
        log.info("字典删除成功: {}", dict.getPid());
    }

    @Override
    public DictDTO findByPid(String pid) {
        if (!StringUtils.hasText(pid)) {
            return null;
        }

          

        Dict dict = dictMapper.findByPid(pid);
        if (dict == null) {
            return null;
        }
        return dictConverter.toDTO(dict);
    }


    @Override
    public DictDTO findByCode(String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }

        Dict dict = dictMapper.findCurrentByCode(code);
        return dict != null ? dictConverter.toDTO(dict) : null;
    }

    // ==================== 查询操作（修复租户上下文） ====================

    @Override
    public Page<DictDTO> findPage(DictQueryRequest request) {
        // 验证租户上下文
          

        // Convert frontend dict type to backend types for query
        // SIMPLE maps to both DYNAMIC and STATIC in the database
        List<String> backendDictTypes = mapDictTypeToBackendList(request.getDictType());

        Page<Dict> pageRequest = new Page<>(request.getPageNum(), request.getPageSize());
        IPage<Dict> pageResult = dictMapper.selectPageList(
            pageRequest,
            MetaContext.getCurrentTenantId(),
            request.getCode(),
            request.getName(),
            backendDictTypes,
            request.getStatus()
        );
        long total = pageResult.getTotal();
        
        // 构建分页结果
        Page<DictDTO> dtoPage = new Page<>();
        dtoPage.setCurrent(request.getPageNum());
        dtoPage.setSize(request.getPageSize());
        dtoPage.setTotal(total);
        dtoPage.setRecords(dictConverter.toDTOList(pageResult.getRecords()));
        
        return dtoPage;
    }

    @Override
    public List<DictDTO> findByTenant() {
        // 验证租户上下文
          

        // Find all active dictionaries (PUBLISHED or DEPRECATED)
        List<Dict> dicts = dictMapper.selectByTenantAndStatus(
            MetaContext.getCurrentTenantId(),
                
            Status.PUBLISHED.getCode()
        );

        return dictConverter.toDTOList(dicts);
    }

    @Override
    public List<DictDTO> findByStatus(String status) {
        // 验证租户上下文
          

        // Validate status value
        try {
            parseStatus(status);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                "无效的状态值: " + status);
        }

        List<Dict> dicts = dictMapper.selectByTenantAndStatus(
            MetaContext.getCurrentTenantId(),
                
            status
        );

        return dictConverter.toDTOList(dicts);
    }

    @Override
    public List<DictDTO> findByType(String dictType) {
        if (!StringUtils.hasText(dictType)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "字典类型不能为空");
        }

        // 验证租户上下文
          

        List<Dict> dicts = dictMapper.selectByType(
            MetaContext.getCurrentTenantId(),
                
            dictType
        );

        return dictConverter.toDTOList(dicts);
    }

    @Override
    public List<DictDTO> search(String keyword) {
        // 验证租户上下文
          

        List<Dict> dicts = dictMapper.searchByKeyword(
            MetaContext.getCurrentTenantId(),
                
            keyword
        );

        return dictConverter.toDTOList(dicts);
    }

    // ==================== 版本管理 ====================

    @Override
    @Transactional
    public DictDTO publish(String pid, String versionNote) {
        log.info("发布字典: {}", pid);

        // 验证租户上下文
        //todo check transiation

        Dict dict = findEntityByPid(pid);

        // 验证是否可以发布
        validateCanPublish(dict);

        // Validate status transition is legal
        Status currentStatus = parseStatus(dict.getStatus());
        if (currentStatus == Status.PUBLISHED) {
            log.info("字典已是发布状态，直接返回: {}", pid);
            return dictConverter.toDTO(dict);
        }
        if (currentStatus != Status.DRAFT && currentStatus != Status.DEPRECATED) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                String.format("Cannot publish from %s status (only DRAFT and DEPRECATED allowed)", dict.getStatus()));
        }

        // 更新发布状态
        dict.setStatus(Status.PUBLISHED.getCode());
        dict.setIsCurrent(true);
        dict.setUpdatedAt(Instant.now());

        dictMapper.updateById(dict);

        log.info("字典发布成功: {} (租户: {})", pid, MetaContext.getCurrentTenantId());
        return dictConverter.toDTO(dict);
    }

    @Override
    @Transactional
    public DictDTO unpublish(String pid) {
        log.info("取消发布字典: {}", pid);

        // 验证租户上下文
          

        Dict dict = findEntityByPid(pid);

        // 验证是否可以取消发布
        validateCanUnpublish(dict);

        // Unpublish should transition to DEPRECATED, not DRAFT
        // DRAFT is for new/editing versions
        dict.setStatus(Status.DEPRECATED.getCode());
        dict.setIsCurrent(false);
        dict.setUpdatedAt(Instant.now());

        dictMapper.updateById(dict);

        log.info("字典标记为废弃: {} (租户: {})", pid, MetaContext.getCurrentTenantId());
        return dictConverter.toDTO(dict);
    }

    @Override
    @Transactional
    public DictDTO createVersion(String pid, String versionNote) {
        log.info("创建字典版本: {}, 备注: {}", pid, versionNote);

        // 验证租户上下文
          

        Dict originalDict = findEntityByPid(pid);

        // 创建新版本
        Dict newVersion = createNewVersion(originalDict, versionNote);

        // 保存新版本
        dictMapper.insert(newVersion);

        log.info("字典版本创建成功: {} (租户: {})", newVersion.getPid(), MetaContext.getCurrentTenantId());
        return dictConverter.toDTO(newVersion);
    }

    @Override
    public List<DictDTO> getVersionHistory(String code) {
        // 验证租户上下文
          

        // 使用Mapper查询版本历史
        List<Dict> dictVersions = dictMapper.selectVersionsByCode(
            MetaContext.getCurrentTenantId(),
                
            code
        );

        return dictConverter.toDTOList(dictVersions);
    }

    // ==================== 统计和验证（修复租户上下文） ====================

    @Override
    public DictStatistics getStatistics() {
        DictStatistics statistics = new DictStatistics();

        // 验证租户上下文
          

        // 统计字典数量
        statistics.setTotalCount(dictMapper.countByConditions(
            MetaContext.getCurrentTenantId(),        null, null, null, null));
        statistics.setPublishedCount(dictMapper.countByConditions(
            MetaContext.getCurrentTenantId(),        null, null, null, Status.PUBLISHED.getCode()));
        statistics.setDraftCount(dictMapper.countByConditions(
            MetaContext.getCurrentTenantId(),        null, null, null, Status.DRAFT.getCode()));

        // Deprecated and Disabled counts
        long deprecatedCount = dictMapper.countByConditions(
            MetaContext.getCurrentTenantId(),        null, null, null, Status.DEPRECATED.getCode());
        long disabledCount = dictMapper.countByConditions(
            MetaContext.getCurrentTenantId(),        null, null, null, Status.DISABLED.getCode());
        
        // Legacy fields mapping
        statistics.setEnabledCount(statistics.getPublishedCount()); // For backward compatibility
        statistics.setDisabledCount(disabledCount);
        
        // 系统字典统计（暂时设为0）
        statistics.setSystemCount(0L);
        statistics.setUserCount(statistics.getTotalCount());
        
        // TODO: 实现更详细的统计逻辑
        statistics.setTypeDistribution(new HashMap<>());
        statistics.setSourceTypeDistribution(new HashMap<>());
        statistics.setVersionStrategyDistribution(new HashMap<>());
        
        // Status distribution
        Map<String, Long> statusDistribution = new HashMap<>();
        statusDistribution.put(Status.DRAFT.getCode(), statistics.getDraftCount());
        statusDistribution.put(Status.PUBLISHED.getCode(), statistics.getPublishedCount());
        statusDistribution.put(Status.DEPRECATED.getCode(), deprecatedCount);
        statusDistribution.put(Status.DISABLED.getCode(), disabledCount);
        statistics.setStatusDistribution(statusDistribution);
        
        return statistics;
    }

    @Override
    public boolean isCodeUnique(String code, String excludePid) {
          

        Long excludeId = null;
        if (StringUtils.hasText(excludePid)) {
            Dict existingDict = findEntityByPid(excludePid);
            if (existingDict != null) {
                excludeId = existingDict.getId();
            }
        }

        long count = dictMapper.countByCode(code, excludeId);
        return count == 0;
    }

    @Override
    public DictValidationResult validateConfig(String pid) {
        log.info("验证字典配置: {}", pid);

        // 验证租户上下文
          

        Dict dict = findEntityByPid(pid);

        DictValidationResult result = new DictValidationResult();

        // 基础验证
        if (!StringUtils.hasText(dict.getCode())) {
            result.addError("字典编码不能为空");
        }

        if (!StringUtils.hasText(dict.getName())) {
            result.addError("字典名称不能为空");
        }

        if (!StringUtils.hasText(dict.getDictType())) {
            result.addError("字典类型不能为空");
        }

        // 租户上下文验证
        if (!MetaContext.getCurrentTenantId().equals(dict.getTenantId())) {
            result.addError("字典不属于当前租户");
        }

        return result;
    }

    // ==================== 批量操作 ====================

    @Override
    @Transactional
    public List<DictDTO> batchCreate(List<DictCreateRequest> requests) {
        return requests.stream()
                .map(this::create)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public int batchUpdateStatus(List<String> pids, String status) {
        log.info("批量更新字典状态: pids={}, status={}", pids.size(), status);

        // 验证租户上下文
          

        Status statusEnum;
        try {
            statusEnum = parseStatus(status);
        } catch (IllegalArgumentException e) {
            log.warn("无效的状态值: {}", status);
            return 0;
        }

        int updateCount = 0;

        for (String pid : pids) {
            try {
                Dict dict = findEntityByPid(pid);
                dict.setStatus(statusEnum.getCode());
                dict.setUpdatedAt(Instant.now());
                dictMapper.updateById(dict);
                updateCount++;
            } catch (Exception e) {
                log.warn("更新字典状态失败: pid={} (租户: {})", pid, MetaContext.getCurrentTenantId(), e);
            }
        }

        return updateCount;
    }

    @Override
    @Transactional
    public int batchDelete(List<String> pids) {
        log.info("批量删除字典: pids={}", pids.size());
        
        int deleteCount = 0;
        
        for (String pid : pids) {
            try {
                delete(pid);
                deleteCount++;
            } catch (Exception e) {
                log.warn("删除字典失败: pid={}", pid, e);
            }
        }
        
        return deleteCount;
    }

    // ==================== 导入导出 ====================

    @Override
    @Transactional
    public DictImportResult importDicts(List<DictCreateRequest> dictData) {
        // 验证租户上下文
          

        log.info("导入字典: 租户={}, 命名空间={}, 环境={}, 数量={}",
                MetaContext.getCurrentTenantId(),        dictData.size());
        
        DictImportResult result = new DictImportResult();
        long startTime = System.currentTimeMillis();
        
        for (DictCreateRequest request : dictData) {
            try {
                // 检查是否已存在
                long count = dictMapper.countByCode(request.getCode(), null);
                if (count > 0) {
                    result.addSkipItem(request.getCode(), request.getName(), "字典编码已存在");
                    continue;
                }
                
                DictDTO dict = create(request);
                result.addSuccessItem(dict);
                
            } catch (Exception e) {
                log.warn("导入字典失败: code={} (租户: {})", request.getCode(), MetaContext.getCurrentTenantId(), e);
                result.addFailureItem(request.getCode(), request.getName(),
                                    "导入失败", e.getMessage());
            }
        }

        result.setDuration(System.currentTimeMillis() - startTime);
        result.generateSummary();

        log.info("字典导入完成: {} (租户: {})", result.getSummary(), MetaContext.getCurrentTenantId());
        return result;
    }

    @Override
    public List<DictDTO> exportDicts(List<String> codes) {
        // 验证租户上下文
          

        log.info("导出字典: 租户={}, 数量={}", MetaContext.getCurrentTenantId(), codes.size());

        List<Dict> dicts = dictMapper.selectByCodes(
            MetaContext.getCurrentTenantId(),
                
            codes
        );

        return dictConverter.toDTOList(dicts);
    }

    // ==================== 级联字典支持 ====================

    @Override
    public List<DictItemData> getCascadeChildren(String parentPid, String parentValue) {
        log.info("获取级联字典子字典: parentPid={}, parentValue={}", parentPid, parentValue);

        try {
            // 验证租户上下文
              

            Dict dict = findEntityByPid(parentPid);
            if (!"cascade".equals(dict.getDictType())) {
                log.warn("字典类型不是级联字典: {} (租户: {})", parentPid, MetaContext.getCurrentTenantId());
                return new ArrayList<>();
            }

            return dictCascadeService.getCascadeChildren(dict.getCode(), parentValue);
            
        } catch (Exception e) {
            log.error("获取级联字典子字典失败: parentPid={}", parentPid, e);
            return new ArrayList<>();
        }
    }

    @Override
    public DictTreeNode buildCascadeTree(String rootPid) {
        log.info("构建级联字典树: rootPid={}", rootPid);

        try {
            // 验证租户上下文
              

            Dict dict = findEntityByPid(rootPid);
            if (!"cascade".equals(dict.getDictType()) && !"tree".equals(dict.getDictType())) {
                log.warn("字典类型不是级联字典: {} (租户: {})", rootPid, MetaContext.getCurrentTenantId());
                return new DictTreeNode();
            }
            
            // 获取所有字典项 - 使用Mapper方法
            List<DictItem> allItems = dictItemMapper.selectByDictIdAndStatus(dict.getId(), "enabled");
            
            if (allItems.isEmpty()) {
                return new DictTreeNode();
            }
            
            // 构建节点映射
            Map<String, DictTreeNode> nodeMap = new HashMap<>();
            Map<String, List<DictTreeNode>> childrenMap = new HashMap<>();
            
            // 创建所有节点
            for (DictItem item : allItems) {
                DictTreeNode node = new DictTreeNode(item.getValue(), item.getLabel(), item.getParentValue());
                node.setSortOrder(item.getSortNo());
                node.setDisabled(!StatusConstants.ENABLED.equals(item.getStatus()));
                nodeMap.put(item.getValue(), node);
                
                String parentKey = item.getParentValue() != null ? item.getParentValue() : "root";
                childrenMap.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(node);
            }
            
            // 构建父子关系
            for (DictItem item : allItems) {
                DictTreeNode node = nodeMap.get(item.getValue());
                List<DictTreeNode> children = childrenMap.get(item.getValue());
                if (children != null) {
                    for (DictTreeNode child : children) {
                        node.addChild(child);
                    }
                }
            }
            
            // 查找根节点
            List<DictTreeNode> rootNodes = childrenMap.get("root");
            if (rootNodes == null || rootNodes.isEmpty()) {
                log.warn("没有找到根节点");
                return new DictTreeNode();
            }
            
            DictTreeNode root;
            if (rootNodes.size() == 1) {
                root = rootNodes.get(0);
            } else {
                // 创建虚拟根节点
                root = new DictTreeNode("root", dict.getName());
                for (DictTreeNode rootNode : rootNodes) {
                    root.addChild(rootNode);
                }
            }
            
            // 排序所有节点的子节点
            root.sortChildren();

            log.info("级联字典树构建成功: rootPid={}, nodeCount={} (租户: {})",
                    rootPid, nodeMap.size(), MetaContext.getCurrentTenantId());
            return root;
            
        } catch (Exception e) {
            log.error("构建级联字典树失败: rootPid={}", rootPid, e);
            return new DictTreeNode();
        }
    }

    // ==================== 字典数据加载 ====================

    @Override
    public DictDataResult loadDictData(String code, String versionStrategy, String pinnedVersion) {
        log.info("加载字典数据: code={}, versionStrategy={}, pinnedVersion={}",
                code, versionStrategy, pinnedVersion);

        // 验证租户上下文
          

        return dictVersionService.loadDictByStrategy(code, versionStrategy, pinnedVersion);
    }

    @Override
    public List<DictDataResult> batchLoadDictData(List<DictLoadRequest> requests) {
        return requests.stream()
                .map(request -> loadDictData(request.getCode(),
                                           request.getVersionStrategy(), request.getPinnedVersion()))
                .collect(Collectors.toList());
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 根据 PID 查找实体（带租户上下文验证）
     */
    private Dict findEntityByPid(String pid) {
        if (!StringUtils.hasText(pid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "PID 不能为空");
        }

        Dict dict = dictMapper.selectByPidWithContext(
            pid,
            MetaContext.getCurrentTenantId()
                  

        );

        if (dict == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                "字典不存在或不属于当前租户: " + pid);
        }

        return dict;
    }

    /**
     * 验证创建请求
     */
    private void validateCreateRequest(DictCreateRequest request) {
        if (request == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "创建请求不能为空");
        }
        
        if (!StringUtils.hasText(request.getCode())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "字典编码不能为空");
        }
        
        if (!StringUtils.hasText(request.getName())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "字典名称不能为空");
        }
        
        if (!StringUtils.hasText(request.getDictType())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "字典类型不能为空");
        }
    }

    /**
     * 验证更新请求
     */
    private void validateUpdateRequest(DictUpdateRequest request) {
        if (request == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "更新请求不能为空");
        }
    }

    /**
     * 验证编码唯一性
     */
    private void validateCodeUnique(String code, String excludePid) {
        Long excludeId = null;
        if (StringUtils.hasText(excludePid)) {
            Dict existingDict = findEntityByPid(excludePid);
            if (existingDict != null) {
                excludeId = existingDict.getId();
            }
        }

        long count = dictMapper.countByCode(code, excludeId);
        if (count > 0) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                "字典编码已存在: " + code);
        }
    }

    /**
     * 验证是否可以删除
     */
    private void validateCanDelete(Dict dict) {
        Status status = parseStatus(dict.getStatus());
        
        if (status == Status.PUBLISHED) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "已发布的字典不能删除，请先废弃或归档");
        }
        
        if (status == Status.ARCHIVED) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "已归档的字典不能删除");
        }
    }

    /**
     * 验证是否可以发布
     */
    private void validateCanPublish(Dict dict) {
        Status status = parseStatus(dict.getStatus());
        
//        if (status == Status.PUBLISHED) {
//            throw new ValidationException(ResponseCode.CommonValidationFailed,
//                "字典已经发布");
//        }
//
//        if (!status.canTransitionTo(Status.PUBLISHED)) {
//            throw new ValidationException(ResponseCode.CommonValidationFailed,
//                String.format("不能从 %s 状态发布字典", status.getCode()));
//        }
        
        DictValidationResult validationResult = validateConfig(dict.getPid());
        if (!validationResult.getValid()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "字典配置无效，无法发布: " + String.join(", ", validationResult.getErrors()));
        }
    }

    /**
     * 验证是否可以取消发布
     */
    private void validateCanUnpublish(Dict dict) {
        Status status = parseStatus(dict.getStatus());
        
        if (status != Status.PUBLISHED) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, 
                "只有已发布的字典才能取消发布");
        }
    }

    /**
     * 创建新版本
     */
    private Dict createNewVersion(Dict original, String versionNote) {
        Dict newVersion = new Dict();

        // 复制基本信息
        newVersion.setPid(UniqueIdGenerator.generate());
        newVersion.setTenantId(MetaContext.getCurrentTenantId());

        newVersion.setCode(original.getCode());
        newVersion.setName(original.getName());
        newVersion.setDescription(original.getDescription());
        newVersion.setDictType(original.getDictType());
        newVersion.setItems(original.getItems());
        newVersion.setExtension(original.getExtension());

        // 设置版本信息
        newVersion.setVersion(original.getVersion() + 1);
        newVersion.setSemver(generateNextSemver(original.getSemver()));
        newVersion.setIsCurrent(false);
        newVersion.setStatus(Status.DRAFT.getCode()); // New version starts as DRAFT

        // 设置审计信息
        newVersion.setCreatedAt(Instant.now());
        newVersion.setUpdatedAt(Instant.now());

        return newVersion;
    }

    private Status parseStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new IllegalArgumentException("status is blank");
        }
        return Arrays.stream(Status.values())
            .filter(candidate -> candidate.getCode().equalsIgnoreCase(status))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown status: " + status));
    }

    /**
     * 生成下一个语义版本号
     */
    private String generateNextSemver(String currentSemver) {
        if (!StringUtils.hasText(currentSemver)) {
            return "1.0.0";
        }
        
        try {
            String[] parts = currentSemver.split("\\.");
            if (parts.length >= 3) {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                int patch = Integer.parseInt(parts[2]);
                return String.format("%d.%d.%d", major, minor, patch + 1);
            }
        } catch (Exception e) {
            log.warn("解析语义版本号失败: {}", currentSemver, e);
        }
        
        return "1.0.0";
    }
    
    // ==================== 字典项管理 ====================

    @Override
    @Transactional
    public DictDTO replaceItems(String dictPid, List<DictCreateRequest.DictItemCreateRequest> items) {
        log.info("替换字典项: dictPid={}, itemCount={}", dictPid, items != null ? items.size() : 0);

        // 验证租户上下文并获取字典
        Dict dict = findEntityByPid(dictPid);

        // 删除现有字典项
        dictItemMapper.deleteByDictId(dict.getId());
        log.info("已删除字典项: dictId={}", dict.getId());

        // 创建新的字典项
        if (items != null && !items.isEmpty()) {
            saveDictItems(dict.getId(), items);
        }

        // 更新字典的更新时间
        dict.setUpdatedAt(Instant.now());
        dictMapper.updateById(dict);

        if (isTreeLikeDict(dict)) {
            dictCascadeService.clearCascadeCache(dict.getCode());
        }

        log.info("字典项替换成功: dictPid={}, newItemCount={}", dictPid, items != null ? items.size() : 0);
        return dictConverter.toDTO(dict);
    }

    @Override
    @Transactional
    public DictDTO replacePluginItems(String dictPid, List<DictCreateRequest.DictItemCreateRequest> items) {
        log.info("Replacing plugin-owned dict items: dictPid={}, itemCount={}", dictPid, items != null ? items.size() : 0);

        Dict dict = findEntityByPid(dictPid);

        // Only delete PLUGIN-sourced items, preserving USER-sourced items
        dictItemMapper.deleteByDictIdAndSource(dict.getId(), "plugin");
        log.info("Deleted PLUGIN-sourced dict items: dictId={}", dict.getId());

        // Create new plugin items with source=PLUGIN
        if (items != null && !items.isEmpty()) {
            savePluginDictItems(dict.getId(), items);
        }

        dict.setUpdatedAt(Instant.now());
        dictMapper.updateById(dict);

        if (isTreeLikeDict(dict)) {
            dictCascadeService.clearCascadeCache(dict.getCode());
        }

        log.info("Plugin dict items replaced: dictPid={}, newItemCount={}", dictPid, items != null ? items.size() : 0);
        return dictConverter.toDTO(dict);
    }

    private boolean isTreeLikeDict(Dict dict) {
        String dictType = dict != null ? dict.getDictType() : null;
        return "tree".equalsIgnoreCase(dictType) || "cascade".equalsIgnoreCase(dictType);
    }

    private void savePluginDictItems(Long dictId, List<DictCreateRequest.DictItemCreateRequest> itemRequests) {
        for (DictCreateRequest.DictItemCreateRequest itemRequest : itemRequests) {
            DictItem item = new DictItem();
            item.setPid(UniqueIdGenerator.generate());
            item.setTenantId(MetaContext.getCurrentTenantId());
            item.setDictId(dictId);
            item.setValue(itemRequest.getValue());
            item.setLabel(itemRequest.getLabel());
            item.setSortNo(itemRequest.getSortOrder() != null ? itemRequest.getSortOrder() : 0);
            item.setParentValue(itemRequest.getParentValue());
            item.setStatus(Boolean.TRUE.equals(itemRequest.getDisabled()) ? "disabled" : "enabled");
            item.setSource("plugin");
            item.setExtra(itemRequest.getExtension());
            item.setCreatedAt(Instant.now());
            item.setUpdatedAt(Instant.now());

            dictItemMapper.insert(item);
        }
    }

    @Override
    @Transactional
    public void markItemsAsPluginSource(String dictPid) {
        Dict dict = findEntityByPid(dictPid);
        dictItemMapper.updateSourceByDictId(dict.getId(), "plugin");
    }

    // ==================== Git-First辅助方法 ====================

    /**
     * 构建DSL文件路径
     *
     * @param code 字典编码
     * @return DSL文件路径
     */
    private String buildDslPath(String code) {
        return String.format("tenant-%d/dsl/dicts/%s.json",
            MetaContext.getCurrentTenantId(), code);
    }


}
