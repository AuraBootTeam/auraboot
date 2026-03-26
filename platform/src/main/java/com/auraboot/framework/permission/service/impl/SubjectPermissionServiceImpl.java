package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.exception.DuplicateException;
import com.auraboot.framework.application.exception.ResourceNotFoundException;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.converter.SubjectPermissionConverter;
import com.auraboot.framework.permission.dto.SubjectPermissionCreateRequest;
import com.auraboot.framework.permission.dto.SubjectPermissionDTO;
import com.auraboot.framework.permission.entity.SubjectPermission;
import com.auraboot.framework.permission.mapper.SubjectPermissionMapper;
import com.auraboot.framework.permission.service.SubjectPermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Subject-Permission服务实现 (V4)
 * 
 * 职责:
 * - Subject-Permission声明管理
 * - 逻辑组一致性校验
 * - 可见性评估（仅用于UI）
 * 
 * @author Kiro
 * @since 2025-01-07
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubjectPermissionServiceImpl implements SubjectPermissionService {
    
    private final SubjectPermissionMapper subjectPermissionMapper;
    private final SubjectPermissionConverter subjectPermissionConverter;
    private final CacheManager cacheManager;
    private final com.auraboot.framework.permission.evaluator.SubjectPermissionEvaluator evaluator;
    
    /**
     * 添加Permission声明
     * 
     * 必须校验逻辑组一致性
     */
    @Override
    @Transactional
    public SubjectPermissionDTO addPermission(SubjectPermissionCreateRequest request) {
        log.info("Adding subject permission: subjectType={}, subjectId={}, permissionId={}",
            request.getSubjectType(), request.getSubjectId(), request.getPermissionId());
        
        // 1. 验证请求
        validateCreateRequest(request);
        
        // 2. 检查重复
        int count = subjectPermissionMapper.countByDeclaration(
            request.getSubjectType(),
            request.getSubjectId(),
            request.getSubjectCode() != null ? request.getSubjectCode() : "",
            request.getPermissionId(),
            request.getLogicGroup(),
            null  // excludeId
        );
        
        if (count > 0) {
            throw new DuplicateException("Subject permission declaration already exists");
        }
        
        // 3. 验证逻辑组一致性
        validateLogicGroupConsistency(
            request.getSubjectType(),
            request.getSubjectId(),
            request.getLogicGroup(),
            request.getGroupLogicType()
        );
        
        // 4. 转换并插入
        SubjectPermission declaration = subjectPermissionConverter.toEntity(request);
        declaration.setPid(com.auraboot.framework.common.util.UniqueIdGenerator.generate());
        declaration.setTenantId(MetaContext.getCurrentTenantId());

        declaration.setSubjectCode(request.getSubjectCode() != null ? request.getSubjectCode() : "");
        declaration.setStatus(StatusConstants.ACTIVE);
        declaration.setCreatedAt(Instant.now());
        declaration.setUpdatedAt(Instant.now());
        declaration.setCreatedBy(MetaContext.getCurrentUserId());
        declaration.setUpdatedBy(MetaContext.getCurrentUserId());
        
        subjectPermissionMapper.insert(declaration);
        
        log.info("Subject permission added: id={}", declaration.getId());
        
        // 5. 失效缓存
        evictSubjectEvaluationCache(request.getSubjectType(), request.getSubjectId());
        
        return subjectPermissionConverter.toDTO(declaration);
    }
    
    /**
     * 批量添加Permission声明
     */
    @Override
    @Transactional
    public List<SubjectPermissionDTO> batchAddPermissions(
            String subjectType,
            Long subjectId,
            List<SubjectPermissionCreateRequest> requests) {
        
        log.info("Batch adding subject permissions: subjectType={}, subjectId={}, count={}", 
            subjectType, subjectId, requests.size());
        
        // 1. 验证所有请求
        for (SubjectPermissionCreateRequest request : requests) {
            request.setSubjectType(subjectType);
            request.setSubjectId(subjectId);
            validateCreateRequest(request);
        }
        
        // 2. 按逻辑组分组并验证一致性
        Map<Integer, List<SubjectPermissionCreateRequest>> byLogicGroup =
            requests.stream()
                .collect(Collectors.groupingBy(SubjectPermissionCreateRequest::getLogicGroup));
        
        for (Map.Entry<Integer, List<SubjectPermissionCreateRequest>> entry : byLogicGroup.entrySet()) {
            Integer logicGroup = entry.getKey();
            List<SubjectPermissionCreateRequest> groupRequests = entry.getValue();
            
            // 检查组内逻辑类型是否一致
            String firstLogicType = groupRequests.get(0).getGroupLogicType();
            boolean allSame = groupRequests.stream()
                .allMatch(r -> r.getGroupLogicType().equals(firstLogicType));
            
            if (!allSame) {
                throw new IllegalArgumentException(
                    String.format("Logic group %d has inconsistent group_logic_type", logicGroup));
            }
            
            // 验证与现有记录的一致性
            validateLogicGroupConsistency(subjectType, subjectId, logicGroup, firstLogicType);
        }
        
        // 3. 批量插入
        List<SubjectPermission> declarations = requests.stream()
            .map(request -> {
                SubjectPermission declaration = subjectPermissionConverter.toEntity(request);
                declaration.setPid(com.auraboot.framework.common.util.UniqueIdGenerator.generate());
                declaration.setTenantId(MetaContext.getCurrentTenantId());

                declaration.setSubjectCode(request.getSubjectCode() != null ? request.getSubjectCode() : "");
                declaration.setStatus(StatusConstants.ACTIVE);
                declaration.setCreatedAt(Instant.now());
                declaration.setUpdatedAt(Instant.now());
                declaration.setCreatedBy(MetaContext.getCurrentUserId());
                declaration.setUpdatedBy(MetaContext.getCurrentUserId());
                return declaration;
            })
            .collect(Collectors.toList());
        
        subjectPermissionMapper.batchInsert(declarations);
        
        log.info("Batch added {} subject permissions", declarations.size());
        
        // 4. 失效缓存
        evictSubjectEvaluationCache(subjectType, subjectId);
        
        return subjectPermissionConverter.toDTOList(declarations);
    }
    
    /**
     * 移除Permission声明
     */
    @Override
    @Transactional
    public void removePermission(Long id) {
        log.info("Removing subject permission: id={}", id);
        
        // 1. 查询现有记录
        SubjectPermission declaration = subjectPermissionMapper.selectById(id);
        if (declaration == null) {
            throw new ResourceNotFoundException("Subject permission not found: " + id);
        }
        
        // 2. 软删除 - use deleteById which respects @TableLogic
        subjectPermissionMapper.deleteById(id);
        
        log.info("Subject permission removed: id={}", id);
        
        // 3. 失效缓存
        evictSubjectEvaluationCache(declaration.getSubjectType(), declaration.getSubjectId());
    }
    
    /**
     * 移除Subject的所有Permission声明
     */
    @Override
    @Transactional
    public void removeAllPermissions(String subjectType, Long subjectId) {
        log.info("Removing all permissions for subject: {}:{}", subjectType, subjectId);
        
        int count = subjectPermissionMapper.deleteBySubject(subjectType, subjectId);
        
        log.info("Removed {} permissions for subject: {}:{}", count, subjectType, subjectId);
        
        // 失效缓存
        evictSubjectEvaluationCache(subjectType, subjectId);
    }
    
    /**
     * 查询Subject的所有Permission声明
     */
    @Override
    public List<SubjectPermissionDTO> findBySubject(String subjectType, Long subjectId) {
        List<SubjectPermission> declarations = subjectPermissionMapper.findBySubject(
            subjectType,
            subjectId
        );
        
        return subjectPermissionConverter.toDTOList(declarations);
    }
    
    /**
     * 根据subject code查询Permission声明
     */
    @Override
    public List<SubjectPermissionDTO> findBySubjectCode(String subjectType, String subjectCode) {
        List<SubjectPermission> declarations = subjectPermissionMapper.findBySubjectCode(
            subjectType,
            subjectCode
        );
        
        return subjectPermissionConverter.toDTOList(declarations);
    }
    
    /**
     * 评估Subject对用户的可见性
     * 
     * 委托给SubjectPermissionEvaluator进行评估
     */
    @Override
    public boolean evaluateVisibility(String subjectType, Long subjectId, Long userId) {
        log.debug("Evaluating visibility for subject: {} {}, user: {}", subjectType, subjectId, userId);
        
        // 委托给Evaluator进行评估
        return evaluator.evaluate(subjectType, subjectId, userId);
    }
    
    /**
     * 批量评估Subject可见性
     * 
     * 委托给SubjectPermissionEvaluator进行批量评估
     */
    @Override
    public Map<Long, Boolean> batchEvaluateVisibility(
            String subjectType,
            List<Long> subjectIds,
            Long userId) {
        
        log.debug("Batch evaluating visibility for {} subjects, user: {}", subjectIds.size(), userId);
        
        // 委托给Evaluator进行批量评估
        return evaluator.batchEvaluate(subjectType, subjectIds, userId);
    }
    
    /**
     * 失效Subject的所有评估缓存
     */
    @Override
    public void evictSubjectEvaluations(String subjectType, Long subjectId) {
        Cache cache = cacheManager.getCache("subject-evaluation");
        if (cache != null) {
            // 简化实现: 清空整个缓存
            // 生产环境可以使用Redis SCAN遍历特定前缀的key
            cache.clear();
            
            log.info("Evicted subject evaluation cache: {}:{}", subjectType, subjectId);
        }
    }
    
    /**
     * 验证逻辑组一致性
     * 
     * 同一逻辑组内的group_logic_type必须一致
     */
    private void validateLogicGroupConsistency(
            String subjectType,
            Long subjectId,
            Integer logicGroup,
            String groupLogicType) {
        
        // 查询现有的逻辑组记录
        List<SubjectPermission> existing = subjectPermissionMapper.findByLogicGroup(
            subjectType,
            subjectId,
            logicGroup
        );
        
        if (existing.isEmpty()) {
            // 新逻辑组，无需校验
            return;
        }
        
        // 检查现有记录的逻辑类型
        String existingLogicType = existing.get(0).getGroupLogicType();
        
        if (!normalizeLogicType(existingLogicType).equals(normalizeLogicType(groupLogicType))) {
            throw new IllegalArgumentException(
                String.format(
                    "Logic group %d already has group_logic_type=%s, cannot add with group_logic_type=%s",
                    logicGroup, existingLogicType, groupLogicType
                )
            );
        }
    }
    
    /**
     * 验证创建请求
     */
    private void validateCreateRequest(SubjectPermissionCreateRequest request) {
        if (request.getSubjectType() == null || request.getSubjectType().isBlank()) {
            throw new IllegalArgumentException("Subject type cannot be empty");
        }
        
        if (request.getSubjectId() == null) {
            throw new IllegalArgumentException("Subject ID cannot be null");
        }
        
        if (request.getPermissionId() == null) {
            throw new IllegalArgumentException("Permission ID cannot be null");
        }
        
        if (request.getLogicGroup() == null) {
            throw new IllegalArgumentException("Logic group cannot be null");
        }
        
        if (request.getGroupLogicType() == null || request.getGroupLogicType().isBlank()) {
            throw new IllegalArgumentException("Group logic type cannot be empty");
        }
        
        String normalizedGroupLogicType = normalizeLogicType(request.getGroupLogicType());
        if (!"and".equals(normalizedGroupLogicType)
            && !"or".equals(normalizedGroupLogicType)) {
            throw new IllegalArgumentException("Group logic type must be AND or OR");
        }

        request.setGroupLogicType(normalizedGroupLogicType);
    }
    
    /**
     * 失效Subject评估缓存
     */
    private void evictSubjectEvaluationCache(String subjectType, Long subjectId) {
        Cache cache = cacheManager.getCache("subject-evaluation");
        if (cache != null) {
            cache.clear();
            log.debug("Evicted subject evaluation cache: {}:{}", subjectType, subjectId);
        }
    }
    
    /**
     * Validate logic group consistency
     * 
     * Checks if all declarations in the same logic group have the same group_logic_type.
     */
    @Override
    public boolean validateLogicGroupConsistency(
            String subjectType,
            Long subjectId,
            Integer logicGroup) {
        
        int distinctCount = subjectPermissionMapper.checkLogicGroupConsistency(
            subjectType,
            subjectId,
            logicGroup
        );
        
        // distinctCount should be 0 (no records) or 1 (consistent)
        return distinctCount <= 1;
    }

    private static String normalizeLogicType(String logicType) {
        return logicType == null ? null : logicType.toLowerCase(Locale.ROOT);
    }
}
