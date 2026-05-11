package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.SodViolationException;
import com.auraboot.framework.meta.dto.SodCheckResult;
import com.auraboot.framework.meta.dto.SodCheckResult.SodViolationDetail;
import com.auraboot.framework.meta.dto.SodRuleCreateRequest;
import com.auraboot.framework.meta.dto.SodRuleUpdateRequest;
import com.auraboot.framework.meta.entity.AuditTrail;
import com.auraboot.framework.meta.entity.SodRule;
import com.auraboot.framework.meta.entity.SodViolationLog;
import com.auraboot.framework.meta.mapper.AuditTrailMapper;
import com.auraboot.framework.meta.mapper.SodRuleMapper;
import com.auraboot.framework.meta.mapper.SodViolationLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Separation of Duties (SoD) service.
 * <p>
 * Manages SoD rules and performs conflict checks during command execution.
 * Checks the audit trail to detect whether the same actor previously executed
 * a conflicting command on the same entity.
 * </p>
 * <p>
 * Rules are cached per tenant for performance. Cache is invalidated
 * on rule creation, update, or deletion.
 * </p>
 *
 * @author AuraBoot Team
 * @since 6.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SodService {

    private final SodRuleMapper sodRuleMapper;
    private final SodViolationLogMapper sodViolationLogMapper;
    private final AuditTrailMapper auditTrailMapper;

    /**
     * Per-tenant cache of enabled SoD rules.
     * Key: tenantId, Value: list of enabled rules.
     */
    private final Map<Long, List<SodRule>> ruleCache = new ConcurrentHashMap<>();

    // ==================== SoD Check ====================

    /**
     * Perform SoD check for a command execution.
     * <p>
     * 1. Find all active rules where command_a or command_b matches the commandCode
     * 2. For each matching rule, find the "other" command
     * 3. Check audit trail: has actorId executed the other command on the same entity?
     * 4. If conflict found, apply enforcement (HARD=block, SOFT=warn, AUDIT_ONLY=log)
     * </p>
     *
     * @param commandCode the command being executed
     * @param actorId     the user executing the command
     * @param entityType  the model/entity type (nullable for GLOBAL scope)
     * @param entityId    the record ID (nullable for SAME_MODEL/GLOBAL scope)
     * @return SodCheckResult with outcome and violation details
     */
    public SodCheckResult checkSod(String commandCode, Long actorId, String actorName,
                                    String entityType, Long entityId) {
        return checkSod(commandCode, actorId, actorName, entityType, entityId, null);
    }

    public SodCheckResult checkSod(String commandCode, Long actorId, String actorName,
                                    String entityType, Long entityId, String entityPid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String normalizedEntityPid = normalizePid(entityPid);

        List<SodRule> matchingRules = getMatchingRules(tenantId, commandCode);
        if (matchingRules.isEmpty()) {
            return SodCheckResult.passed();
        }

        List<SodViolationDetail> violations = new ArrayList<>();
        String worstOutcome = "passed";

        for (SodRule rule : matchingRules) {
            // Determine which is the "other" command
            String otherCommand = rule.getCommandA().equals(commandCode)
                    ? rule.getCommandB() : rule.getCommandA();

            // Check audit trail for conflict
            boolean hasConflict = checkAuditTrailForConflict(
                    tenantId, actorId, otherCommand, entityType, entityId, normalizedEntityPid, rule.getEntityScope());

            if (hasConflict) {
                String enforcementOutcome = mapEnforcementToOutcome(rule.getEnforcement());

                violations.add(SodViolationDetail.builder()
                        .ruleCode(rule.getRuleCode())
                        .ruleName(rule.getRuleName())
                        .conflictingCommand(otherCommand)
                        .enforcement(rule.getEnforcement())
                        .entityScope(rule.getEntityScope())
                        .message(String.format(
                                "SoD conflict: actor %d already executed '%s', cannot execute '%s' (rule: %s, scope: %s)",
                                actorId, otherCommand, commandCode, rule.getRuleCode(), rule.getEntityScope()))
                        .build());

                // Record violation log
                logViolation(tenantId, rule, actorId, actorName,
                        commandCode, otherCommand, entityType, entityId, normalizedEntityPid, enforcementOutcome);

                // Track the worst enforcement level
                worstOutcome = resolveWorstOutcome(worstOutcome, enforcementOutcome);
            }
        }

        if (violations.isEmpty()) {
            return SodCheckResult.passed();
        }

        SodCheckResult result = SodCheckResult.builder()
                .outcome(worstOutcome)
                .violations(violations)
                .build();

        // Throw exception for HARD enforcement
        if ("blocked".equals(worstOutcome)) {
            throw new SodViolationException(violations.get(0).getMessage(), result);
        }

        return result;
    }

    // ==================== Rule CRUD ====================

    @Transactional
    public SodRule createRule(SodRuleCreateRequest request) {
        validate(request);

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        SodRule rule = new SodRule();
        rule.setTenantId(tenantId);
        rule.setRuleCode(request.getRuleCode());
        rule.setRuleName(request.getRuleName());
        rule.setDescription(request.getDescription());
        rule.setCommandA(request.getCommandA());
        rule.setCommandB(request.getCommandB());
        rule.setEntityScope(request.getEntityScope() != null ? request.getEntityScope() : "same_record");
        rule.setEnforcement(request.getEnforcement() != null ? request.getEnforcement() : "hard");
        rule.setEnabled(request.getEnabled() != null ? request.getEnabled() : true);
        rule.setCreatedBy(userId);
        rule.setUpdatedBy(userId);
        rule.setDeletedFlag(false);

        sodRuleMapper.insert(rule);
        invalidateCache(tenantId);

        log.info("SoD rule created: {} (tenant={})", rule.getRuleCode(), tenantId);
        return rule;
    }

    @Transactional
    public SodRule updateRule(Long id, SodRuleUpdateRequest request) {
        SodRule rule = sodRuleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(ResponseCode.BadParam, "SoD rule not found: " + id);
        }

        Long userId = MetaContext.getCurrentUserId();

        if (StringUtils.hasText(request.getRuleName())) {
            rule.setRuleName(request.getRuleName());
        }
        if (request.getDescription() != null) {
            rule.setDescription(request.getDescription());
        }
        if (StringUtils.hasText(request.getCommandA())) {
            rule.setCommandA(request.getCommandA());
        }
        if (StringUtils.hasText(request.getCommandB())) {
            rule.setCommandB(request.getCommandB());
        }
        if (StringUtils.hasText(request.getEntityScope())) {
            rule.setEntityScope(request.getEntityScope());
        }
        if (StringUtils.hasText(request.getEnforcement())) {
            rule.setEnforcement(request.getEnforcement());
        }
        if (request.getEnabled() != null) {
            rule.setEnabled(request.getEnabled());
        }
        rule.setUpdatedBy(userId);

        sodRuleMapper.updateById(rule);
        invalidateCache(rule.getTenantId());

        log.info("SoD rule updated: {} (id={})", rule.getRuleCode(), id);
        return rule;
    }

    @Transactional
    public void deleteRule(Long id) {
        SodRule rule = sodRuleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(ResponseCode.BadParam, "SoD rule not found: " + id);
        }

        // Soft delete via MyBatis Plus @TableLogic
        sodRuleMapper.deleteById(id);
        invalidateCache(rule.getTenantId());

        log.info("SoD rule deleted: {} (id={})", rule.getRuleCode(), id);
    }

    public List<SodRule> listRules() {
        LambdaQueryWrapper<SodRule> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(SodRule::getRuleCode);
        return sodRuleMapper.selectList(wrapper);
    }

    public SodRule getRule(Long id) {
        SodRule rule = sodRuleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(ResponseCode.BadParam, "SoD rule not found: " + id);
        }
        return rule;
    }

    // ==================== Violation Queries ====================

    public List<SodViolationLog> getViolations(Instant startTime, Instant endTime) {
        return sodViolationLogMapper.findByTimeRange(startTime, endTime);
    }

    public List<SodViolationLog> getViolationsByActor(Long actorId) {
        return sodViolationLogMapper.findByActor(actorId);
    }

    @Transactional
    public SodViolationLog overrideViolation(Long violationId, Long overrideBy, String reason) {
        SodViolationLog violation = sodViolationLogMapper.selectById(violationId);
        if (violation == null) {
            throw new BusinessException(ResponseCode.BadParam, "SoD violation not found: " + violationId);
        }

        violation.setOverrideBy(overrideBy);
        violation.setOverrideReason(reason);
        sodViolationLogMapper.updateById(violation);

        log.info("SoD violation {} overridden by user {} with reason: {}", violationId, overrideBy, reason);
        return violation;
    }

    // ==================== Internal Methods ====================

    /**
     * Get rules matching a command code from cache (or load from DB).
     */
    private List<SodRule> getMatchingRules(Long tenantId, String commandCode) {
        List<SodRule> allRules = ruleCache.computeIfAbsent(tenantId, tid -> {
            log.debug("Loading SoD rules for tenant {}", tid);
            return sodRuleMapper.findAllEnabled();
        });

        return allRules.stream()
                .filter(r -> commandCode.equals(r.getCommandA()) || commandCode.equals(r.getCommandB()))
                .toList();
    }

    /**
     * Check the audit trail to see if the actor has executed the conflicting command
     * on the same entity (respecting entity scope).
     */
    private boolean checkAuditTrailForConflict(Long tenantId, Long actorId, String conflictingCommand,
                                                String entityType, Long entityId, String entityPid, String entityScope) {
        switch (entityScope) {
            case "same_record":
                // Check if the same actor executed the conflicting command on the exact same record
                if (entityType == null) {
                    return false; // Cannot check SAME_RECORD without entity info
                }
                List<AuditTrail> recordTrail;
                if (StringUtils.hasText(entityPid)) {
                    recordTrail = auditTrailMapper.getByEntityPid(tenantId, entityType, entityPid);
                } else if (entityId != null) {
                    recordTrail = auditTrailMapper.getByEntity(tenantId, entityType, entityId);
                } else {
                    return false;
                }
                return recordTrail.stream()
                        .anyMatch(t -> conflictingCommand.equals(t.getCommandCode())
                                && actorId.equals(t.getActorId()));

            case "same_model":
                // Check if the same actor executed the conflicting command on any record of the same model
                if (entityType == null) {
                    return false;
                }
                List<AuditTrail> commandTrail = auditTrailMapper.getByCommand(tenantId, conflictingCommand);
                return commandTrail.stream()
                        .anyMatch(t -> actorId.equals(t.getActorId())
                                && entityType.equals(t.getEntityType()));

            case "global":
                // Check if the same actor has ever executed the conflicting command
                List<AuditTrail> globalTrail = auditTrailMapper.getByCommand(tenantId, conflictingCommand);
                return globalTrail.stream()
                        .anyMatch(t -> actorId.equals(t.getActorId()));

            default:
                log.warn("Unknown SoD entity scope: {}, treating as SAME_RECORD", entityScope);
                return false;
        }
    }

    /**
     * Log a violation to the ab_sod_violation_log table.
     */
    private void logViolation(Long tenantId, SodRule rule, Long actorId, String actorName,
                               String commandAttempted, String conflictingCommand,
                               String entityType, Long entityId, String entityPid, String outcome) {
        try {
            SodViolationLog violation = new SodViolationLog();
            violation.setTenantId(tenantId);
            violation.setRuleId(rule.getId());
            violation.setRuleCode(rule.getRuleCode());
            violation.setActorId(actorId);
            violation.setActorName(actorName);
            violation.setCommandAttempted(commandAttempted);
            violation.setConflictingCommand(conflictingCommand);
            violation.setConflictingActorId(actorId); // same actor for SoD
            violation.setEntityType(entityType);
            violation.setEntityId(entityId);
            violation.setEntityPid(entityPid);
            violation.setEnforcement(rule.getEnforcement());
            violation.setOutcome(outcome);

            sodViolationLogMapper.insert(violation);
        } catch (Exception e) {
            // Don't let violation logging failure break command execution
            log.error("Failed to log SoD violation for rule {}: {}", rule.getRuleCode(), e.getMessage());
        }
    }

    private String mapEnforcementToOutcome(String enforcement) {
        return switch (enforcement) {
            case "hard" -> "blocked";
            case "soft" -> "warned";
            case "audit_only" -> "logged";
            default -> "logged";
        };
    }

    /**
     * Determine the worst (highest severity) outcome.
     * Priority: BLOCKED > WARNED > LOGGED > PASSED
     */
    private String resolveWorstOutcome(String current, String incoming) {
        int currentPriority = outcomePriority(current);
        int incomingPriority = outcomePriority(incoming);
        return incomingPriority > currentPriority ? incoming : current;
    }

    private int outcomePriority(String outcome) {
        return switch (outcome) {
            case "blocked" -> 3;
            case "warned" -> 2;
            case "logged" -> 1;
            default -> 0;
        };
    }

    private void invalidateCache(Long tenantId) {
        ruleCache.remove(tenantId);
        log.debug("SoD rule cache invalidated for tenant {}", tenantId);
    }

    private String normalizePid(String pid) {
        return StringUtils.hasText(pid) ? pid.trim() : null;
    }

    private void validate(SodRuleCreateRequest request) {
        if (!StringUtils.hasText(request.getRuleCode())) {
            throw new BusinessException(ResponseCode.BadParam, "ruleCode is required");
        }
        if (!StringUtils.hasText(request.getRuleName())) {
            throw new BusinessException(ResponseCode.BadParam, "ruleName is required");
        }
        if (!StringUtils.hasText(request.getCommandA())) {
            throw new BusinessException(ResponseCode.BadParam, "commandA is required");
        }
        if (!StringUtils.hasText(request.getCommandB())) {
            throw new BusinessException(ResponseCode.BadParam, "commandB is required");
        }
        if (request.getCommandA().equals(request.getCommandB())) {
            throw new BusinessException(ResponseCode.BadParam, "commandA and commandB must be different");
        }

        // Validate entityScope
        String scope = request.getEntityScope();
        if (scope != null && !List.of("same_record", "same_model", "global").contains(scope)) {
            throw new BusinessException(ResponseCode.BadParam, "entityScope must be SAME_RECORD, SAME_MODEL, or GLOBAL");
        }

        // Validate enforcement
        String enforcement = request.getEnforcement();
        if (enforcement != null && !List.of("hard", "soft", "audit_only").contains(enforcement)) {
            throw new BusinessException(ResponseCode.BadParam, "enforcement must be HARD, SOFT, or AUDIT_ONLY");
        }
    }
}
