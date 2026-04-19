package com.auraboot.framework.bpm.service;

import com.auraboot.framework.bpm.rule.DroolsEngineService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.UserRoleMapper;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.mapper.UserMapper;
import com.auraboot.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Assignee resolver service.
 * Resolves task assignees based on rule type and context.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssigneeResolverService {

    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final TenantMemberService tenantMemberService;
    private final DroolsEngineService droolsEngineService;
    private final UserMapper userMapper;
    private final ExpressionParser spelParser = new SpelExpressionParser();

    /**
     * Resolve assignees based on a rule configuration and process context.
     *
     * @param ruleType the rule type (SPECIFIC_USER, ROLE, DEPARTMENT, STARTER, EXPRESSION, etc.)
     * @param ruleConfig the rule configuration
     * @param context process context variables
     * @return list of resolved user IDs
     */
    public List<String> resolve(String ruleType, Map<String, Object> ruleConfig, Map<String, Object> context) {
        if (ruleType == null || ruleConfig == null) {
            return List.of();
        }

        return switch (ruleType.toLowerCase()) {
            case "specific_user" -> resolveSpecificUser(ruleConfig);
            case "role" -> resolveByRole(ruleConfig);
            case "department" -> resolveByDepartment(ruleConfig);
            case "starter" -> resolveStarter(context);
            case "starter_manager" -> resolveStarterManager(context);
            case "previous_handler" -> resolvePreviousHandler(context);
            case "expression" -> resolveExpression(ruleConfig, context);
            case "rule" -> resolveByDroolsRule(ruleConfig, context);
            default -> {
                log.warn("Unknown assignee rule type: {}", ruleType);
                yield List.of();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private List<String> resolveSpecificUser(Map<String, Object> config) {
        Object userIds = config.get("userIds");
        if (userIds instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        if (userIds instanceof String str) {
            return List.of(str.split(","));
        }
        return List.of();
    }

    private List<String> resolveByRole(Map<String, Object> config) {
        // Support both roleIds (numeric Long IDs) and roleCodes (string codes from plugin processes.json).
        // For plugin processes.json the assigneeId is a role code like "wd_manager"; we resolve it
        // to a numeric role ID via RoleMapper.findIdByCode before looking up members.
        Object roleIds = config.get("roleIds");
        List<Long> numericRoleIds;
        if (roleIds instanceof List<?> list) {
            numericRoleIds = list.stream()
                    .map(id -> resolveRoleIdOrCode(id.toString()))
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        } else if (roleIds instanceof String str) {
            numericRoleIds = Arrays.stream(str.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(this::resolveRoleIdOrCode)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        } else {
            log.warn("ROLE assignee rule: no roleIds provided in config: {}", config);
            return List.of();
        }

        if (numericRoleIds.isEmpty()) {
            log.warn("ROLE assignee rule: all role references resolved to null: {}", config);
            return List.of();
        }

        List<Long> memberIds = numericRoleIds.stream()
                .flatMap(roleId -> userRoleMapper.findMemberIdsByRoleId(roleId).stream())
                .distinct()
                .toList();

        // Convert memberIds → TenantMember.userId (numeric) → ab_user.pid (ULID).
        // BPM canonical user identity is the user PID (ULID), which is what
        // BpmSecurityUtil.getCurrentUserId() / MetaContext.getCurrentUsername() returns.
        return memberIds.stream()
                .map(tenantMemberService::getById)
                .filter(Objects::nonNull)
                .map(TenantMember::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .map(userMapper::findPidByUserId)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Resolve a role reference that may be either a numeric ID (Long) or a string role code.
     * Plugin processes.json uses string codes (e.g. "wd_manager"); Page Designer uses numeric PIDs.
     *
     * @param idOrCode  numeric string or role code
     * @return numeric role ID, or null if not found
     */
    private Long resolveRoleIdOrCode(String idOrCode) {
        if (idOrCode == null || idOrCode.isBlank()) return null;
        try {
            // If it parses as a long, treat it as a direct numeric role ID.
            return Long.parseLong(idOrCode.trim());
        } catch (NumberFormatException e) {
            // Non-numeric: treat as role code — look up by code in current tenant.
            Long tenantId = MetaContext.getCurrentTenantId();
            if (tenantId == null) {
                log.warn("ROLE assignee: cannot resolve role code '{}' — no tenant in context", idOrCode);
                return null;
            }
            Long roleId = roleMapper.findIdByCode(tenantId, idOrCode.trim());
            if (roleId == null) {
                log.warn("ROLE assignee: role code '{}' not found in tenant {}", idOrCode, tenantId);
            }
            return roleId;
        }
    }

    private List<String> resolveByDepartment(Map<String, Object> config) {
        Object explicitUserIds = config.get("userIds");
        if (explicitUserIds instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(Object::toString).toList();
        }
        if (explicitUserIds instanceof String str && !str.isBlank()) {
            return Arrays.stream(str.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        log.warn("DEPARTMENT assignee rule requires explicit userIds until org service is implemented. Config: {}", config);
        return List.of();
    }

    private List<String> resolveStarter(Map<String, Object> context) {
        // Prefer SmartEngine canonical request-map key (injected by
        // ProcessEngineService.startProcess); fall back to AuraBoot conventions.
        Object startUserId = context.get(RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID);
        if (startUserId == null) {
            startUserId = context.get("_startUserId");
        }
        if (startUserId == null) {
            startUserId = context.get("startUserId");
        }
        if (startUserId != null) {
            return List.of(startUserId.toString());
        }
        return List.of();
    }

    private List<String> resolveStarterManager(Map<String, Object> context) {
        Object starterManagerUserId = context.get("_starterManagerUserId");
        if (starterManagerUserId == null) {
            starterManagerUserId = context.get("starterManagerUserId");
        }
        if (starterManagerUserId != null) {
            return List.of(starterManagerUserId.toString());
        }
        log.warn("STARTER_MANAGER assignee rule requires starterManagerUserId in context until org hierarchy service is implemented. Starter={}",
                context.get("_startUserId"));
        return List.of();
    }

    private List<String> resolvePreviousHandler(Map<String, Object> context) {
        Object previousHandler = context.get("_previousHandler");
        if (previousHandler != null) {
            return List.of(previousHandler.toString());
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<String> resolveByDroolsRule(Map<String, Object> config, Map<String, Object> context) {
        String ruleCode = (String) config.get("ruleCode");
        if (ruleCode == null) {
            log.warn("RULE assignee: no ruleCode in config: {}", config);
            return List.of();
        }
        try {
            Map<String, Object> result = droolsEngineService.evaluate(ruleCode, context);
            // Rule should return "assigneeUserId" (single) or "candidateUserIds" (list)
            Object assignee = result.get("assigneeUserId");
            if (assignee != null) {
                return List.of(assignee.toString());
            }
            Object candidates = result.get("candidateUserIds");
            if (candidates instanceof List<?> list) {
                return list.stream().map(Object::toString).toList();
            }
            if (candidates instanceof String str) {
                return Arrays.asList(str.split(","));
            }
            log.warn("RULE assignee: rule '{}' returned no assignee result: {}", ruleCode, result);
            return List.of();
        } catch (Exception e) {
            log.error("RULE assignee execution failed: ruleCode={}", ruleCode, e);
            return List.of();
        }
    }

    private List<String> resolveExpression(Map<String, Object> config, Map<String, Object> context) {
        Object expression = config.get("expression");
        if (expression == null) return List.of();
        try {
            SimpleEvaluationContext evalContext = SimpleEvaluationContext.forReadOnlyDataBinding().build();
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                evalContext.setVariable(entry.getKey(), entry.getValue());
            }
            Object result = spelParser.parseExpression(expression.toString()).getValue(evalContext);
            if (result instanceof List<?> list) {
                return list.stream().map(Object::toString).toList();
            }
            if (result instanceof String str) {
                return Arrays.asList(str.split(","));
            }
            if (result != null) {
                return List.of(result.toString());
            }
        } catch (Exception e) {
            log.warn("Failed to evaluate assignee expression '{}': {}", expression, e.getMessage());
        }
        return List.of();
    }
}
