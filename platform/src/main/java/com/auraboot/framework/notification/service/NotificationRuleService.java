package com.auraboot.framework.notification.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.dto.QueryCondition.Operator;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.notification.dto.NotificationRuleDTO;
import com.auraboot.framework.notification.dto.NotificationRuleRequest;
import com.auraboot.framework.notification.dto.NotificationRuleTestResult;
import com.auraboot.framework.notification.entity.NotificationRule;
import com.auraboot.framework.notification.mapper.NotificationRuleMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for managing and evaluating notification rules.
 *
 * <p>Rules define triggers (EVENT or SCHEDULED), conditions (model + filter),
 * and actions (channel + template + recipients).
 *
 * @since 5.2.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationRuleService {

    private final NotificationRuleMapper ruleMapper;
    private final DynamicDataService dynamicDataService;
    private final ObjectMapper objectMapper;
    private final DataSource dataSource;

    // -------------------------------------------------------------------------
    // Schema auto-init
    // -------------------------------------------------------------------------

    /**
     * Ensure the ab_notification_rule table exists on startup.
     * This avoids requiring a manual migration step during development.
     */
    @PostConstruct
    public void initSchema() {
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ab_notification_rule (
                    id              BIGSERIAL PRIMARY KEY,
                    tenant_id       BIGINT NOT NULL,
                    code            VARCHAR(100) NOT NULL,
                    name            VARCHAR(200) NOT NULL,
                    description     TEXT,
                    enabled         BOOLEAN DEFAULT TRUE,
                    trigger_type    VARCHAR(30) NOT NULL DEFAULT 'scheduled',
                    trigger_config  JSONB,
                    condition_model_code  VARCHAR(100),
                    condition_filter      JSONB,
                    action_channel        VARCHAR(30),
                    action_template_code  VARCHAR(100),
                    recipient_type        VARCHAR(30),
                    recipient_field       VARCHAR(100),
                    last_evaluated_at     TIMESTAMPTZ,
                    send_count            INT DEFAULT 0,
                    deleted_flag    BOOLEAN DEFAULT FALSE,
                    created_at      TIMESTAMPTZ DEFAULT NOW(),
                    updated_at      TIMESTAMPTZ DEFAULT NOW()
                )
                """);
            jdbc.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_rule_tenant_code
                    ON ab_notification_rule (tenant_id, code)
                    WHERE deleted_flag = FALSE
                """);
            jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_notification_rule_tenant
                    ON ab_notification_rule (tenant_id)
                """);
            log.info("NotificationRuleService: table ab_notification_rule ready");
        } catch (Exception e) {
            log.warn("NotificationRuleService: schema init warning: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // CRUD
    // -------------------------------------------------------------------------

    /** List all rules for the current tenant. */
    public List<NotificationRuleDTO> listRules() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ruleMapper.findAllByTenant(tenantId)
                .stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /** Get a single rule by ID. */
    public NotificationRuleDTO getRule(Long id) {
        Long tenantId = MetaContext.getCurrentTenantId();
        NotificationRule rule = ruleMapper.selectOne(
                new QueryWrapper<NotificationRule>()
                        .eq("id", id)
                        .eq("tenant_id", tenantId)
                        .and(w -> w.eq("deleted_flag", false).or().isNull("deleted_flag"))
        );
        if (rule == null) {
            throw new BusinessException("Notification rule not found: " + id);
        }
        return toDTO(rule);
    }

    /** Create a new notification rule. */
    @Transactional
    public NotificationRuleDTO createRule(NotificationRuleRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        NotificationRule rule = fromRequest(request);
        rule.setTenantId(tenantId);
        rule.setSendCount(0);
        try {
            ruleMapper.insert(rule);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("Rule with code '" + request.getCode() + "' already exists");
        }
        log.info("Created notification rule '{}' (id={}) for tenant {}", rule.getCode(), rule.getId(), tenantId);
        return toDTO(rule);
    }

    /** Update an existing notification rule. */
    @Transactional
    public NotificationRuleDTO updateRule(Long id, NotificationRuleRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        NotificationRule existing = ruleMapper.selectOne(
                new QueryWrapper<NotificationRule>()
                        .eq("id", id)
                        .eq("tenant_id", tenantId)
                        .and(w -> w.eq("deleted_flag", false).or().isNull("deleted_flag"))
        );
        if (existing == null) {
            throw new BusinessException("Notification rule not found: " + id);
        }
        applyRequest(existing, request);
        ruleMapper.updateById(existing);
        log.info("Updated notification rule '{}' (id={})", existing.getCode(), id);
        return toDTO(existing);
    }

    /** Soft-delete a notification rule. */
    @Transactional
    public void deleteRule(Long id) {
        Long tenantId = MetaContext.getCurrentTenantId();
        UpdateWrapper<NotificationRule> uw = new UpdateWrapper<>();
        uw.eq("id", id).eq("tenant_id", tenantId);
        uw.set("deleted_flag", true).set("updated_at", Instant.now());
        int affected = ruleMapper.update(null, uw);
        if (affected == 0) {
            throw new BusinessException("Notification rule not found: " + id);
        }
        log.info("Deleted notification rule id={}", id);
    }

    /** Toggle enabled/disabled for a rule. */
    @Transactional
    public NotificationRuleDTO toggleEnabled(Long id, boolean enabled) {
        Long tenantId = MetaContext.getCurrentTenantId();
        NotificationRule rule = ruleMapper.selectOne(
                new QueryWrapper<NotificationRule>()
                        .eq("id", id)
                        .eq("tenant_id", tenantId)
                        .and(w -> w.eq("deleted_flag", false).or().isNull("deleted_flag"))
        );
        if (rule == null) {
            throw new BusinessException("Notification rule not found: " + id);
        }
        rule.setEnabled(enabled);
        ruleMapper.updateById(rule);
        return toDTO(rule);
    }

    // -------------------------------------------------------------------------
    // Rule evaluation
    // -------------------------------------------------------------------------

    /**
     * Test-evaluate a rule — runs the condition query and returns matched count.
     * Does NOT send any notifications.
     */
    public NotificationRuleTestResult testEvaluateRule(Long id) {
        NotificationRuleDTO dto = getRule(id);
        return evaluateCondition(dto);
    }

    /**
     * Evaluate rule conditions and return matched record count + sample data.
     * Used both for test-evaluation and the actual scheduled engine.
     */
    public NotificationRuleTestResult evaluateCondition(NotificationRuleDTO rule) {
        if (rule.getConditionModelCode() == null || rule.getConditionModelCode().isBlank()) {
            return NotificationRuleTestResult.builder()
                    .success(false)
                    .error("Rule has no condition model configured")
                    .build();
        }
        try {
            List<QueryCondition> conditions = parseFilterConditions(rule.getConditionFilter());
            DynamicQueryRequest queryRequest = DynamicQueryRequest.builder()
                    .pageNum(1)
                    .pageSize(5)
                    .conditions(conditions)
                    .build();

            PaginationResult<Map<String, Object>> result =
                    dynamicDataService.list(rule.getConditionModelCode(), queryRequest);

            int total = result.getTotal() != null ? (int) (long) result.getTotal() : 0;
            List<Object> samples = result.getRecords() != null
                    ? new ArrayList<>(result.getRecords())
                    : List.of();

            String summary = total == 0
                    ? "No records match the condition filter"
                    : String.format("%d record(s) match — %d sample(s) shown", total, samples.size());

            return NotificationRuleTestResult.builder()
                    .success(true)
                    .matchedCount(total)
                    .sampleRecords(samples)
                    .summary(summary)
                    .build();

        } catch (Exception e) {
            log.warn("Rule evaluation failed for rule id={}: {}", rule.getId(), e.getMessage());
            return NotificationRuleTestResult.builder()
                    .success(false)
                    .error("Evaluation error: " + e.getMessage())
                    .build();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<QueryCondition> parseFilterConditions(String filterJson) {
        if (filterJson == null || filterJson.isBlank() || filterJson.equals("[]")) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(
                    filterJson, new TypeReference<>() {});
            List<QueryCondition> conditions = new ArrayList<>();
            for (Map<String, Object> item : raw) {
                QueryCondition cond = new QueryCondition();
                cond.setFieldName((String) item.get("fieldName"));
                String opStr = (String) item.getOrDefault("operator", "EQ");
                try {
                    cond.setOperator(Operator.valueOf(opStr.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    cond.setOperator(Operator.EQ);
                }
                cond.setValue(item.get("value"));
                conditions.add(cond);
            }
            return conditions;
        } catch (Exception e) {
            log.warn("Failed to parse condition filter JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private NotificationRuleDTO toDTO(NotificationRule entity) {
        NotificationRuleDTO dto = new NotificationRuleDTO();
        dto.setId(entity.getId());
        dto.setCode(entity.getCode());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setEnabled(entity.getEnabled());
        dto.setTriggerType(entity.getTriggerType());
        dto.setTriggerConfig(entity.getTriggerConfig());
        dto.setConditionModelCode(entity.getConditionModelCode());
        dto.setConditionFilter(entity.getConditionFilter());
        dto.setActionChannel(entity.getActionChannel());
        dto.setActionTemplateCode(entity.getActionTemplateCode());
        dto.setRecipientType(entity.getRecipientType());
        dto.setRecipientField(entity.getRecipientField());
        dto.setLastEvaluatedAt(entity.getLastEvaluatedAt());
        dto.setSendCount(entity.getSendCount());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private NotificationRule fromRequest(NotificationRuleRequest req) {
        NotificationRule rule = new NotificationRule();
        applyRequest(rule, req);
        return rule;
    }

    private void applyRequest(NotificationRule rule, NotificationRuleRequest req) {
        rule.setCode(req.getCode());
        rule.setName(req.getName());
        rule.setDescription(req.getDescription());
        rule.setEnabled(req.getEnabled() != null ? req.getEnabled() : true);
        rule.setTriggerType(req.getTriggerType());
        rule.setTriggerConfig(req.getTriggerConfig());
        rule.setConditionModelCode(req.getConditionModelCode());
        rule.setConditionFilter(req.getConditionFilter());
        rule.setActionChannel(req.getActionChannel());
        rule.setActionTemplateCode(req.getActionTemplateCode());
        rule.setRecipientType(req.getRecipientType());
        rule.setRecipientField(req.getRecipientField());
    }
}
