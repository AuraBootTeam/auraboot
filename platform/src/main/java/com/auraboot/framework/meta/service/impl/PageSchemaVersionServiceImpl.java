package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.PageSchemaVersionDTO;
import com.auraboot.framework.meta.dto.PageSchemaVersionComparisonDTO;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.entity.PageSchemaHistory;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.mapper.PageSchemaHistoryMapper;
import com.auraboot.framework.meta.service.PageSchemaVersionService;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.DateUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 页面Schema版本管理服务实现类
 * 提供版本控制、历史记录管理和版本比较功能
 * 
 * @author AuraBoot Framework
 * @since 1.0.0
 */
@Slf4j
@Service
@Transactional
public class PageSchemaVersionServiceImpl implements PageSchemaVersionService {

    @Autowired
    private PageSchemaMapper pageSchemaMapper;

    @Autowired
    private PageSchemaHistoryMapper pageSchemaHistoryMapper;

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== 版本创建和管理 ====================

    @Override
    public PageSchemaVersionDTO createVersion(String pagePid, String operation, String operatorPid, String description) {
        log.info("创建版本历史记录: pagePid={}, operation={}", pagePid, operation);
        
        // 参数验证
        validateCreateVersionParams(pagePid, operation, operatorPid);
        
        // 获取当前页面Schema
        PageSchema currentSchema = findPageSchemaByPid(pagePid);
        
        // 创建历史记录
        PageSchemaHistory history = new PageSchemaHistory();
        history.setPid(pagePid);
        history.setOp(operation);
        history.setOpBy(operatorPid);
        history.setOpAt(Instant.now());
        history.setCreatedAt(Instant.now());
        
        // 创建快照数据
        Map<String, Object> snapshot = createSchemaSnapshot(currentSchema);
        history.setSnapshot(snapshot);
        
        // 保存历史记录
        pageSchemaHistoryMapper.insert(history);
        
        log.info("版本历史记录创建成功: historyId={}", history.getId());
        return convertToVersionDTO(history, description);
    }

    @Override
    public List<PageSchemaVersionDTO> getVersionHistory(String pagePid) {
        log.info("获取版本历史列表: pagePid={}", pagePid);
        
        if (!StringUtils.hasText(pagePid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "页面PID不能为空");
        }
        
        List<PageSchemaHistory> histories = pageSchemaHistoryMapper.findByPagePid(pagePid);
        return histories.stream()
                .map(history -> convertToVersionDTO(history, null))
                .collect(Collectors.toList());
    }

    @Override
    public PageSchemaVersionDTO getVersionById(Long historyId) {
        log.info("获取版本详细信息: historyId={}", historyId);
        
        if (historyId == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "历史记录ID不能为空");
        }
        
        PageSchemaHistory history = pageSchemaHistoryMapper.selectById(historyId);
        if (history == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "版本记录不存在: " + historyId);
        }
        
        return convertToVersionDTO(history, null);
    }

    @Override
    public PageSchemaVersionDTO getLatestVersion(String pagePid) {
        log.info("获取最新版本: pagePid={}", pagePid);
        
        if (!StringUtils.hasText(pagePid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "页面PID不能为空");
        }
        
        PageSchemaHistory latestHistory = pageSchemaHistoryMapper.findLatestByPagePid(pagePid);
        if (latestHistory == null) {
            return null;
        }
        
        return convertToVersionDTO(latestHistory, null);
    }

    // ==================== 版本比较功能 ====================

    @Override
    public PageSchemaVersionComparisonDTO compareVersions(Long sourceHistoryId, Long targetHistoryId) {
        log.info("比较版本差异: sourceId={}, targetId={}", sourceHistoryId, targetHistoryId);
        
        // 参数验证
        if (sourceHistoryId == null || targetHistoryId == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "版本ID不能为空");
        }
        
        // 获取版本记录
        PageSchemaHistory sourceHistory = pageSchemaHistoryMapper.selectById(sourceHistoryId);
        PageSchemaHistory targetHistory = pageSchemaHistoryMapper.selectById(targetHistoryId);
        
        if (sourceHistory == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "源版本不存在: " + sourceHistoryId);
        }
        if (targetHistory == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "目标版本不存在: " + targetHistoryId);
        }
        
        // 执行比较
        return performVersionComparison(sourceHistory, targetHistory);
    }

    @Override
    public PageSchemaVersionComparisonDTO compareWithCurrent(String pagePid, Long historyId) {
        log.info("与当前版本比较: pagePid={}, historyId={}", pagePid, historyId);
        
        // 参数验证
        if (!StringUtils.hasText(pagePid) || historyId == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "参数不能为空");
        }
        
        // 获取当前页面Schema
        PageSchema currentSchema = findPageSchemaByPid(pagePid);
        
        // 获取历史版本
        PageSchemaHistory historyVersion = pageSchemaHistoryMapper.selectById(historyId);
        if (historyVersion == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "历史版本不存在: " + historyId);
        }
        
        // 创建当前版本的临时历史记录用于比较
        PageSchemaHistory currentHistory = new PageSchemaHistory();
        currentHistory.setId(0L); // 临时ID
        currentHistory.setPid(pagePid);
        currentHistory.setOp("current");
        currentHistory.setOpAt(Instant.now());
        currentHistory.setSnapshot(createSchemaSnapshot(currentSchema));
        
        return performVersionComparison(historyVersion, currentHistory);
    }

    // ==================== 版本回滚功能 ====================

    @Override
    @Transactional
    public PageSchemaVersionDTO rollbackToVersion(String pagePid, Long historyId, String operatorPid, String reason) {
        log.info("回滚到指定版本: pagePid={}, historyId={}, operator={}", pagePid, historyId, operatorPid);
        
        // 参数验证
        validateRollbackParams(pagePid, historyId, operatorPid);
        
        // 验证是否可以回滚
        if (!canRollbackToVersion(pagePid, historyId)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "无法回滚到指定版本");
        }
        
        // 获取目标版本
        PageSchemaHistory targetHistory = pageSchemaHistoryMapper.selectById(historyId);
        if (targetHistory == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "目标版本不存在: " + historyId);
        }
        
        // 获取当前页面Schema
        PageSchema currentSchema = findPageSchemaByPid(pagePid);
        
        // 先创建当前版本的备份
        createVersion(pagePid, "backup_before_rollback", operatorPid, "回滚前备份");
        
        // 从快照恢复数据
        restoreSchemaFromSnapshot(currentSchema, targetHistory.getSnapshot());
        
        // 更新页面Schema
        pageSchemaMapper.updateById(currentSchema);
        
        // 创建回滚操作的历史记录
        return createVersion(pagePid, "rollback", operatorPid, 
            String.format("回滚到版本 %d: %s", historyId, reason));
    }

    @Override
    public boolean canRollbackToVersion(String pagePid, Long historyId) {
        try {
            // 基本参数验证
            if (!StringUtils.hasText(pagePid) || historyId == null) {
                return false;
            }
            
            // 检查页面是否存在
            PageSchema currentSchema = pageSchemaMapper.selectByPid(pagePid);
            if (currentSchema == null || currentSchema.getDeletedFlag()) {
                return false;
            }
            
            // 检查目标版本是否存在
            PageSchemaHistory targetHistory = pageSchemaHistoryMapper.selectById(historyId);
            if (targetHistory == null || !pagePid.equals(targetHistory.getPid())) {
                return false;
            }
            
            // 检查快照数据是否完整
            Map<String, Object> snapshot = targetHistory.getSnapshot();
            if (snapshot == null || snapshot.isEmpty()) {
                return false;
            }
            
            // 检查是否有必要的字段
            return snapshot.containsKey("name") && snapshot.containsKey("blocks");
            
        } catch (Exception e) {
            log.error("检查回滚条件时发生错误: pagePid={}, historyId={}", pagePid, historyId, e);
            return false;
        }
    }

    // ==================== 版本发布管理 ====================

    @Override
    @Transactional
    public PageSchemaVersionDTO publishVersion(String pagePid, Long historyId, String operatorPid) {
        log.info("发布版本: pagePid={}, historyId={}, operator={}", pagePid, historyId, operatorPid);
        
        // 参数验证
        validatePublishParams(pagePid, historyId, operatorPid);
        
        // 获取目标版本
        PageSchemaHistory targetHistory = pageSchemaHistoryMapper.selectById(historyId);
        if (targetHistory == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "目标版本不存在: " + historyId);
        }
        
        // 获取当前页面Schema
        PageSchema currentSchema = findPageSchemaByPid(pagePid);
        
        // 检查是否已经是当前版本
        Map<String, Object> targetSnapshot = targetHistory.getSnapshot();
        if (isCurrentVersion(currentSchema, targetSnapshot)) {
            // 直接发布当前版本
            currentSchema.setStatus(StatusConstants.PUBLISHED);
            currentSchema.setPublishedAt(Instant.now());
            pageSchemaMapper.updateById(currentSchema);
        } else {
            // 先回滚到目标版本，再发布
            rollbackToVersion(pagePid, historyId, operatorPid, "发布指定版本");

            // 重新获取更新后的Schema
            currentSchema = findPageSchemaByPid(pagePid);
            currentSchema.setStatus(StatusConstants.PUBLISHED);
            currentSchema.setPublishedAt(Instant.now());
            pageSchemaMapper.updateById(currentSchema);
        }
        
        // 创建发布操作的历史记录
        return createVersion(pagePid, "publish", operatorPid, 
            String.format("发布版本 %d", historyId));
    }

    @Override
    @Transactional
    public PageSchemaVersionDTO unpublishVersion(String pagePid, Long historyId, String operatorPid) {
        log.info("取消发布版本: pagePid={}, historyId={}, operator={}", pagePid, historyId, operatorPid);
        
        // 参数验证
        validatePublishParams(pagePid, historyId, operatorPid);
        
        // 获取当前页面Schema
        PageSchema currentSchema = findPageSchemaByPid(pagePid);
        
        // 取消发布
        currentSchema.setStatus(StatusConstants.DRAFT);
        currentSchema.setPublishedAt(null);
        pageSchemaMapper.updateById(currentSchema);
        
        // 创建取消发布操作的历史记录
        return createVersion(pagePid, "unpublish", operatorPid, 
            String.format("取消发布版本 %d", historyId));
    }

    // ==================== 版本统计和查询 ====================

    @Override
    public Long countVersions(String pagePid) {
        if (!StringUtils.hasText(pagePid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "页面PID不能为空");
        }
        
        return pageSchemaHistoryMapper.countByPagePid(pagePid);
    }

    @Override
    public Long countVersionsByOperation(String pagePid, String operation) {
        if (!StringUtils.hasText(pagePid) || !StringUtils.hasText(operation)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "参数不能为空");
        }
        
        return pageSchemaHistoryMapper.countByPagePidAndOp(pagePid, operation);
    }

    @Override
    public List<PageSchemaVersionDTO> getPublishedVersions(String pagePid) {
        if (!StringUtils.hasText(pagePid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "页面PID不能为空");
        }
        
        List<PageSchemaHistory> publishedHistories = pageSchemaHistoryMapper.findByPagePidAndOp(pagePid, "publish");
        return publishedHistories.stream()
                .map(history -> convertToVersionDTO(history, null))
                .collect(Collectors.toList());
    }

    // ==================== 版本清理和维护 ====================

    @Override
    @Transactional
    public Integer cleanupOldVersions(String pagePid, Integer keepCount) {
        log.info("清理过期版本: pagePid={}, keepCount={}", pagePid, keepCount);
        
        if (!StringUtils.hasText(pagePid) || keepCount == null || keepCount < 1) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "参数无效");
        }
        
        // 获取所有版本，按时间倒序
        List<PageSchemaHistory> allHistories = pageSchemaHistoryMapper.findByPagePid(pagePid);
        
        if (allHistories.size() <= keepCount) {
            return 0; // 无需清理
        }
        
        // 保留最新的keepCount个版本，删除其余的
        List<PageSchemaHistory> toDelete = allHistories.subList(keepCount, allHistories.size());
        int deletedCount = 0;
        
        for (PageSchemaHistory history : toDelete) {
            // 不删除重要的操作记录（如发布、回滚等）
            if (!isImportantOperation(history.getOp())) {
                pageSchemaHistoryMapper.deleteById(history.getId());
                deletedCount++;
            }
        }
        
        log.info("清理完成: 删除了 {} 个版本记录", deletedCount);
        return deletedCount;
    }

    @Override
    public boolean validateVersionIntegrity(Long historyId) {
        try {
            if (historyId == null) {
                return false;
            }
            
            PageSchemaHistory history = pageSchemaHistoryMapper.selectById(historyId);
            if (history == null) {
                return false;
            }
            
            Map<String, Object> snapshot = history.getSnapshot();
            if (snapshot == null || snapshot.isEmpty()) {
                return false;
            }
            
            // 验证必要字段
            String[] requiredFields = {"name", "title", "kind", "blocks"};
            for (String field : requiredFields) {
                if (!snapshot.containsKey(field) || snapshot.get(field) == null) {
                    return false;
                }
            }

            // 验证JSON格式
            Object blocks = snapshot.get("blocks");
            if (blocks instanceof String) {
                try {
                    objectMapper.readTree((String) blocks);
                } catch (Exception e) {
                    return false;
                }
            }
            
            return true;
            
        } catch (Exception e) {
            log.error("验证版本完整性时发生错误: historyId={}", historyId, e);
            return false;
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 验证创建版本的参数
     */
    private void validateCreateVersionParams(String pagePid, String operation, String operatorPid) {
        if (!StringUtils.hasText(pagePid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "页面PID不能为空");
        }
        if (!StringUtils.hasText(operation)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "操作类型不能为空");
        }
        if (!StringUtils.hasText(operatorPid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "操作人PID不能为空");
        }
    }

    /**
     * 验证回滚参数
     */
    private void validateRollbackParams(String pagePid, Long historyId, String operatorPid) {
        if (!StringUtils.hasText(pagePid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "页面PID不能为空");
        }
        if (historyId == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "历史记录ID不能为空");
        }
        if (!StringUtils.hasText(operatorPid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "操作人PID不能为空");
        }
    }

    /**
     * 验证发布参数
     */
    private void validatePublishParams(String pagePid, Long historyId, String operatorPid) {
        if (!StringUtils.hasText(pagePid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "页面PID不能为空");
        }
        if (historyId == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "历史记录ID不能为空");
        }
        if (!StringUtils.hasText(operatorPid)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "操作人PID不能为空");
        }
    }

    /**
     * 根据PID查找页面Schema
     */
    private PageSchema findPageSchemaByPid(String pid) {
        PageSchema pageSchema = pageSchemaMapper.selectByPid(pid);
        if (pageSchema == null || pageSchema.getDeletedFlag()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "页面配置不存在: " + pid);
        }
        return pageSchema;
    }

    /**
     * 创建Schema快照
     */
    private Map<String, Object> createSchemaSnapshot(PageSchema schema) {
        Map<String, Object> snapshot = new HashMap<>();
        
        // 基础信息
        snapshot.put("name", schema.getName());
        snapshot.put("title", schema.getTitle());
        snapshot.put("description", schema.getDescription());
        snapshot.put("kind", schema.getKind());

        // Schema内容
        snapshot.put("blocks", schema.getBlocks());
        snapshot.put("layout", schema.getLayout());
        snapshot.put("metaInfo", schema.getMetaInfo());
        snapshot.put("tags", schema.getTags());
        
        // 版本信息
        snapshot.put("version", schema.getVersion());
        snapshot.put("semver", schema.getSemver());
        snapshot.put("rowVersion", schema.getRowVersion());
        snapshot.put("isCurrent", schema.getIsCurrent());
        
        // 发布信息
        snapshot.put("publishedAt", schema.getPublishedAt());
        
        // 模板信息
        snapshot.put("isTemplate", schema.getIsTemplate());
        snapshot.put("templateCategory", schema.getTemplateCategory());
        snapshot.put("sortWeight", schema.getSortWeight());
        

        snapshot.put("extension", schema.getExtension());
        snapshot.put("status", schema.getStatus());
        
        return snapshot;
    }

    /**
     * 从快照恢复Schema
     */
    private void restoreSchemaFromSnapshot(PageSchema schema, Map<String, Object> snapshot) {
        // 基础信息
        schema.setName((String) snapshot.get("name"));
        schema.setTitle((String) snapshot.get("title"));
        schema.setDescription((String) snapshot.get("description"));
        schema.setKind((String) snapshot.get("kind"));

        // Schema内容
        schema.setBlocks((String) snapshot.get("blocks"));
        schema.setLayout((String) snapshot.get("layout"));
        schema.setMetaInfo((String) snapshot.get("metaInfo"));
        schema.setTags((String) snapshot.get("tags"));
        
        // 版本信息（递增版本号）
        schema.setVersion(schema.getVersion() + 1);
        schema.setSemver((String) snapshot.get("semver"));
        schema.setIsCurrent(true);
        
        // 模板信息
        schema.setIsTemplate((Boolean) snapshot.get("isTemplate"));
        schema.setTemplateCategory((String) snapshot.get("templateCategory"));
        schema.setSortWeight((Integer) snapshot.get("sortWeight"));

        
        // 更新时间
        schema.setUpdatedAt(Instant.now());
    }

    /**
     * 转换为版本DTO
     */
    private PageSchemaVersionDTO convertToVersionDTO(PageSchemaHistory history, String description) {
        PageSchemaVersionDTO dto = new PageSchemaVersionDTO();
        dto.setId(history.getId());
        dto.setPagePid(history.getPid());
        dto.setOperation(history.getOp());
        dto.setOperatorPid(history.getOpBy());
        dto.setOperationTime(DateUtil.toUtcLocalDateTime(history.getOpAt()));
        dto.setSnapshot(history.getSnapshot());
        dto.setDescription(description);
        
        // 从快照中提取版本信息
        Map<String, Object> snapshot = history.getSnapshot();
        if (snapshot != null) {
            dto.setVersion((Integer) snapshot.get("version"));
            dto.setSemver((String) snapshot.get("semver"));
            dto.setIsCurrent((Boolean) snapshot.get("isCurrent"));
        }
        
        return dto;
    }

    /**
     * 执行版本比较
     */
    private PageSchemaVersionComparisonDTO performVersionComparison(PageSchemaHistory sourceHistory, PageSchemaHistory targetHistory) {
        PageSchemaVersionComparisonDTO comparison = new PageSchemaVersionComparisonDTO();
        
        // 设置版本信息
        comparison.setSourceVersion(createVersionInfo(sourceHistory));
        comparison.setTargetVersion(createVersionInfo(targetHistory));
        
        // 计算差异
        List<PageSchemaVersionComparisonDTO.FieldDifference> differences = calculateDifferences(
            sourceHistory.getSnapshot(), targetHistory.getSnapshot());
        comparison.setDifferences(differences);
        
        // 生成摘要
        PageSchemaVersionComparisonDTO.ComparisonSummary summary = createComparisonSummary(differences);
        comparison.setSummary(summary);
        
        return comparison;
    }

    /**
     * 创建版本信息
     */
    private PageSchemaVersionComparisonDTO.VersionInfo createVersionInfo(PageSchemaHistory history) {
        PageSchemaVersionComparisonDTO.VersionInfo versionInfo = new PageSchemaVersionComparisonDTO.VersionInfo();
        versionInfo.setHistoryId(history.getId());
        versionInfo.setPagePid(history.getPid());
        versionInfo.setOperation(history.getOp());
        versionInfo.setOperationTime(DateUtil.toUtcLocalDateTime(history.getOpAt()));
        versionInfo.setOperatorPid(history.getOpBy());
        
        Map<String, Object> snapshot = history.getSnapshot();
        if (snapshot != null) {
            versionInfo.setVersion((Integer) snapshot.get("version"));
            versionInfo.setSemver((String) snapshot.get("semver"));
        }
        
        return versionInfo;
    }

    /**
     * 计算字段差异
     */
    private List<PageSchemaVersionComparisonDTO.FieldDifference> calculateDifferences(
            Map<String, Object> sourceSnapshot, Map<String, Object> targetSnapshot) {
        
        List<PageSchemaVersionComparisonDTO.FieldDifference> differences = new ArrayList<>();
        
        // 获取所有字段
        Set<String> allFields = new HashSet<>();
        allFields.addAll(sourceSnapshot.keySet());
        allFields.addAll(targetSnapshot.keySet());
        
        for (String field : allFields) {
            Object sourceValue = sourceSnapshot.get(field);
            Object targetValue = targetSnapshot.get(field);
            
            PageSchemaVersionComparisonDTO.FieldDifference diff = compareFieldValues(field, sourceValue, targetValue);
            if (diff != null) {
                differences.add(diff);
            }
        }
        
        return differences;
    }

    /**
     * 比较字段值
     */
    private PageSchemaVersionComparisonDTO.FieldDifference compareFieldValues(String fieldPath, Object sourceValue, Object targetValue) {
        if (Objects.equals(sourceValue, targetValue)) {
            return null; // 无差异
        }
        
        PageSchemaVersionComparisonDTO.FieldDifference diff = new PageSchemaVersionComparisonDTO.FieldDifference();
        diff.setFieldPath(fieldPath);
        diff.setSourceValue(sourceValue);
        diff.setTargetValue(targetValue);
        
        if (sourceValue == null) {
            diff.setType(PageSchemaVersionComparisonDTO.DifferenceType.ADDED);
            diff.setDescription("新增字段: " + fieldPath);
        } else if (targetValue == null) {
            diff.setType(PageSchemaVersionComparisonDTO.DifferenceType.REMOVED);
            diff.setDescription("删除字段: " + fieldPath);
        } else {
            diff.setType(PageSchemaVersionComparisonDTO.DifferenceType.MODIFIED);
            diff.setDescription("修改字段: " + fieldPath);
        }
        
        return diff;
    }

    /**
     * 创建比较摘要
     */
    private PageSchemaVersionComparisonDTO.ComparisonSummary createComparisonSummary(
            List<PageSchemaVersionComparisonDTO.FieldDifference> differences) {
        
        PageSchemaVersionComparisonDTO.ComparisonSummary summary = new PageSchemaVersionComparisonDTO.ComparisonSummary();
        summary.setTotalDifferences(differences.size());
        
        int addedCount = 0, removedCount = 0, modifiedCount = 0;
        Map<String, Integer> changesByCategory = new HashMap<>();
        
        for (PageSchemaVersionComparisonDTO.FieldDifference diff : differences) {
            switch (diff.getType()) {
                case ADDED:
                    addedCount++;
                    break;
                case REMOVED:
                    removedCount++;
                    break;
                case MODIFIED:
                    modifiedCount++;
                    break;
            }
            
            // 按字段类别统计
            String category = getFieldCategory(diff.getFieldPath());
            changesByCategory.merge(category, 1, Integer::sum);
        }
        
        summary.setAddedFields(addedCount);
        summary.setRemovedFields(removedCount);
        summary.setModifiedFields(modifiedCount);
        summary.setChangesByCategory(changesByCategory);
        
        // 判断是否有重大变更
        summary.setHasMajorChanges(hasMajorChanges(differences));
        
        return summary;
    }

    /**
     * 获取字段类别
     */
    private String getFieldCategory(String fieldPath) {
        if (fieldPath.startsWith("dsl")) {
            return "Schema定义";
        } else if (fieldPath.startsWith("meta")) {
            return "元信息";
        } else if (fieldPath.equals("name") || fieldPath.equals("title") || fieldPath.equals("description")) {
            return "基础信息";
        } else if (fieldPath.contains("publish")) {
            return "发布信息";
        } else if (fieldPath.contains("template")) {
            return "模板信息";
        } else {
            return "其他";
        }
    }

    /**
     * 判断是否有重大变更
     */
    private boolean hasMajorChanges(List<PageSchemaVersionComparisonDTO.FieldDifference> differences) {
        String[] majorFields = {"name", "kind", "blocks"};
        
        return differences.stream()
                .anyMatch(diff -> Arrays.asList(majorFields).contains(diff.getFieldPath()));
    }

    /**
     * 检查是否为当前版本
     */
    private boolean isCurrentVersion(PageSchema currentSchema, Map<String, Object> targetSnapshot) {
        try {
            Map<String, Object> currentSnapshot = createSchemaSnapshot(currentSchema);
            
            // 比较关键字段
            String[] keyFields = {"name", "title", "kind", "blocks", "version"};
            for (String field : keyFields) {
                if (!Objects.equals(currentSnapshot.get(field), targetSnapshot.get(field))) {
                    return false;
                }
            }
            
            return true;
        } catch (Exception e) {
            log.error("比较版本时发生错误", e);
            return false;
        }
    }

    /**
     * 检查是否为重要操作
     */
    private boolean isImportantOperation(String operation) {
        String[] importantOps = {"create", "publish", "rollback", "restore"};
        return Arrays.asList(importantOps).contains(operation);
    }
}
