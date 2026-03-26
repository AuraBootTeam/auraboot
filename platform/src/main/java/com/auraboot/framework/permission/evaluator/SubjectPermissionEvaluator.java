package com.auraboot.framework.permission.evaluator;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.entity.SubjectPermission;
import com.auraboot.framework.permission.mapper.SubjectPermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Subject-Permission统一评估器 (V4)
 * 
 * 支持复杂的逻辑组合:
 * - AND/OR逻辑组
 * - is_negated (取反)
 * - 多逻辑组组合（组间OR）
 * 
 * 关键约束:
 * - 必须在运行期检查逻辑组一致性
 * - is_negated仅用于UI可见性，不参与后端授权
 * - 组间使用OR逻辑
 * 
 * @author Kiro
 * @since 2025-01-07
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubjectPermissionEvaluator {
    
    private final SubjectPermissionMapper subjectPermissionMapper;
    private final UserPermissionService userPermissionService;
    
    /**
     * 评估Subject是否对用户可见
     * 
     * 评估逻辑:
     * 1. 无声明 = 默认可见
     * 2. 按logic_group分组
     * 3. 评估每个逻辑组（组内AND/OR）
     * 4. 组间OR（任意一个逻辑组满足即可见）
     * 
     * @param subjectType Subject类型 (MENU, PAGE, BUTTON, etc.)
     * @param subjectId Subject ID
     * @param userId 用户ID
     * @return true=可见, false=不可见
     */
    public boolean evaluate(String subjectType, Long subjectId, Long userId) {
        log.debug("Evaluating subject visibility: subjectType={}, subjectId={}, userId={}", 
            subjectType, subjectId, userId);
        
        // 1. 查询Subject的所有Permission声明
        List<SubjectPermission> declarations = subjectPermissionMapper.findBySubject(
            subjectType,
            subjectId
        );
        
        if (declarations.isEmpty()) {
            // 无声明 = 默认可见
            log.debug("No permission declarations found, default to visible");
            return true;
        }
        
        // 2. 查询用户拥有的Permission集合
        Set<Long> userPermissions = userPermissionService.getUserPermissionIds(userId);
        
        log.debug("User has {} permissions", userPermissions.size());
        
        // 3. 按logic_group分组
        Map<Integer, List<SubjectPermission>> groups =
            declarations.stream()
                .collect(Collectors.groupingBy(SubjectPermission::getLogicGroup));
        
        log.debug("Found {} logic groups", groups.size());
        
        // 4. 评估每个逻辑组（组间OR）
        for (Map.Entry<Integer, List<SubjectPermission>> entry : groups.entrySet()) {
            Integer groupId = entry.getKey();
            List<SubjectPermission> groupDeclarations = entry.getValue();
            
            boolean groupResult = evaluateLogicGroup(groupDeclarations, userPermissions);
            
            log.debug("Logic group {} evaluation result: {}", groupId, groupResult);
            
            if (groupResult) {
                // 任意一个逻辑组满足 = 可见
                log.debug("Subject is visible (logic group {} satisfied)", groupId);
                return true;
            }
        }
        
        // 所有逻辑组都不满足 = 不可见
        log.debug("Subject is not visible (no logic group satisfied)");
        return false;
    }
    
    /**
     * 评估单个逻辑组
     * 
     * @param declarations 逻辑组内的所有声明
     * @param userPermissions 用户拥有的Permission集合
     * @return true=满足, false=不满足
     */
    private boolean evaluateLogicGroup(
            List<SubjectPermission> declarations,
            Set<Long> userPermissions) {
        
        if (declarations.isEmpty()) {
            return true;
        }
        
        // 获取逻辑类型（组内必须一致）
        String groupLogicType = normalizeLogicType(declarations.get(0).getGroupLogicType());
        
        // 校验一致性（运行期检查）
        boolean allSameLogicType = declarations.stream()
            .allMatch(d -> normalizeLogicType(d.getGroupLogicType()).equals(groupLogicType));
        
        if (!allSameLogicType) {
            // Data inconsistency — log error but degrade gracefully (deny visibility)
            Set<String> logicTypes = declarations.stream()
                .map(SubjectPermission::getGroupLogicType)
                .collect(Collectors.toSet());

            log.error("Logic group has inconsistent group_logic_type: {} — denying visibility as fallback", logicTypes);
            return false;  // Fail-secure: inconsistent data = not visible
        }
        
        // 按logic_order排序
        List<SubjectPermission> sorted = declarations.stream()
            .sorted(Comparator.comparing(SubjectPermission::getLogicOrder))
            .collect(Collectors.toList());
        
        // 根据逻辑类型评估
        if ("and".equals(groupLogicType)) {
            return evaluateAND(sorted, userPermissions);
        } else if ("or".equals(groupLogicType)) {
            return evaluateOR(sorted, userPermissions);
        } else {
            throw new IllegalStateException("Invalid group_logic_type: " + groupLogicType);
        }
    }
    
    /**
     * AND逻辑评估: 所有条件都必须满足
     * 
     * @param declarations 声明列表（已排序）
     * @param userPermissions 用户Permission集合
     * @return true=所有条件满足, false=任意条件不满足
     */
    private boolean evaluateAND(
            List<SubjectPermission> declarations,
            Set<Long> userPermissions) {
        
        for (SubjectPermission decl : declarations) {
            boolean hasPermission = userPermissions.contains(decl.getPermissionId());
            
            // 应用is_negated
            boolean result = decl.getIsNegated() ? !hasPermission : hasPermission;
            
            log.trace("AND evaluation: permissionId={}, hasPermission={}, isNegated={}, result={}",
                decl.getPermissionId(), hasPermission, decl.getIsNegated(), result);
            
            if (!result) {
                // AND逻辑: 任意一个不满足 = 整体不满足
                return false;
            }
        }
        
        // 所有条件都满足
        return true;
    }
    
    /**
     * OR逻辑评估: 任意条件满足即可
     * 
     * @param declarations 声明列表（已排序）
     * @param userPermissions 用户Permission集合
     * @return true=任意条件满足, false=所有条件不满足
     */
    private boolean evaluateOR(
            List<SubjectPermission> declarations,
            Set<Long> userPermissions) {
        
        for (SubjectPermission decl : declarations) {
            boolean hasPermission = userPermissions.contains(decl.getPermissionId());
            
            // 应用is_negated
            boolean result = decl.getIsNegated() ? !hasPermission : hasPermission;
            
            log.trace("OR evaluation: permissionId={}, hasPermission={}, isNegated={}, result={}",
                decl.getPermissionId(), hasPermission, decl.getIsNegated(), result);
            
            if (result) {
                // OR逻辑: 任意一个满足 = 整体满足
                return true;
            }
        }
        
        // 所有条件都不满足
        return false;
    }

    private static String normalizeLogicType(String logicType) {
        return logicType == null ? null : logicType.toLowerCase(Locale.ROOT);
    }
    
    /**
     * 批量评估多个Subject的可见性
     * 
     * 优化: 一次性查询所有Subject的声明和用户Permission
     * 
     * @param subjectType Subject类型
     * @param subjectIds Subject ID列表
     * @param userId 用户ID
     * @return Subject ID -> 可见性的映射
     */
    public Map<Long, Boolean> batchEvaluate(
            String subjectType,
            List<Long> subjectIds,
            Long userId) {
        
        log.debug("Batch evaluating {} subjects for user: {}", subjectIds.size(), userId);
        
        // 1. 批量查询所有Subject的声明
        List<SubjectPermission> allDeclarations = subjectPermissionMapper.findBySubjects(
            subjectType,
            subjectIds
        );
        
        // 2. 查询用户Permission（只查一次）
        Set<Long> userPermissions = userPermissionService.getUserPermissionIds(userId);
        
        // 3. 按Subject分组
        Map<Long, List<SubjectPermission>> bySubject =
            allDeclarations.stream()
                .collect(Collectors.groupingBy(SubjectPermission::getSubjectId));
        
        // 4. 逐个评估
        Map<Long, Boolean> results = new HashMap<>();
        for (Long subjectId : subjectIds) {
            List<SubjectPermission> declarations =
                bySubject.getOrDefault(subjectId, Collections.emptyList());
            
            boolean result = evaluateDeclarations(declarations, userPermissions);
            results.put(subjectId, result);
        }
        
        log.debug("Batch evaluation completed: {} subjects", results.size());
        
        return results;
    }
    
    /**
     * 评估声明列表
     * 
     * @param declarations 声明列表
     * @param userPermissions 用户Permission集合
     * @return true=可见, false=不可见
     */
    private boolean evaluateDeclarations(
            List<SubjectPermission> declarations,
            Set<Long> userPermissions) {
        
        if (declarations.isEmpty()) {
            return true;  // 无声明 = 默认可见
        }
        
        // 按logic_group分组
        Map<Integer, List<SubjectPermission>> groups =
            declarations.stream()
                .collect(Collectors.groupingBy(SubjectPermission::getLogicGroup));
        
        // 评估每个逻辑组（组间OR）
        for (List<SubjectPermission> groupDeclarations : groups.values()) {
            boolean groupResult = evaluateLogicGroup(groupDeclarations, userPermissions);
            if (groupResult) {
                return true;  // 任意一个逻辑组满足 = 可见
            }
        }
        
        return false;  // 所有逻辑组都不满足 = 不可见
    }
}
