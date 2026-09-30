package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.notification.service.NotificationService;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.dto.SemanticMetricAlertDTO;
import com.auraboot.framework.semantic.dto.SemanticMetricAlertRequest;
import com.auraboot.framework.semantic.dto.SemanticQueryResponse;
import com.auraboot.framework.semantic.entity.AbSemanticMetric;
import com.auraboot.framework.semantic.entity.AbSemanticMetricAlert;
import com.auraboot.framework.semantic.entity.AbSemanticModel;
import com.auraboot.framework.semantic.exception.SemanticValidationException;
import com.auraboot.framework.semantic.mapper.AbSemanticMetricAlertMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticMetricMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticModelMapper;
import com.auraboot.framework.userattribute.service.UserAttributeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Threshold alerts over published semantic metrics (BI rectification R2).
 *
 * <p>Evaluation runs the metric through the governed semantic pipeline as the
 * alert's creator: the compiled RLS and metric-level permissions of the
 * creator decide whether the value is reachable at all. A creator who lost
 * access fails the evaluation closed — the alert logs and skips, it never
 * falls back to an ungoverned read.
 *
 * <p>Silence window: a persistently-breaching metric notifies once, then goes
 * quiet for {@code silence_minutes}; evaluation still records the breach
 * (last_evaluated_at) so dashboards can show a continuously-firing alert.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticMetricAlertService {

    private static final Set<String> COMPARATORS = Set.of("gt", "gte", "lt", "lte");
    private static final Set<String> STATUSES = Set.of("active", "paused");

    private final AbSemanticMetricAlertMapper alertMapper;
    private final AbSemanticMetricMapper metricMapper;
    private final AbSemanticModelMapper modelMapper;
    private final SemanticQueryService queryService;
    private final NotificationService notificationService;
    private final UserAttributeService userAttributeService;

    // ==================== CRUD ====================

    public SemanticMetricAlertDTO create(SemanticMetricAlertRequest request) {
        Long tenantId = MetaContext.get().getTenantId();
        Long userId = MetaContext.get().getUserId();
        validateRequest(request);
        AbSemanticMetric metric = requireMetric(tenantId, request.getMetricPid());

        OffsetDateTime now = OffsetDateTime.now();
        AbSemanticMetricAlert alert = new AbSemanticMetricAlert();
        alert.setPid(UniqueIdGenerator.generate());
        alert.setTenantId(tenantId);
        applyRequest(alert, request, now);
        alert.setCreatedBy(userId);
        alert.setCreatedAt(now);
        alert.setUpdatedAt(now);
        alert.setDeletedFlag(false);
        if (alert.getAlertStatus() == null) alert.setAlertStatus("active");
        alertMapper.insert(alert);
        return toDto(alert, metric);
    }

    public List<SemanticMetricAlertDTO> list() {
        Long tenantId = MetaContext.get().getTenantId();
        List<SemanticMetricAlertDTO> out = new ArrayList<>();
        for (AbSemanticMetricAlert alert : alertMapper.listByTenant(tenantId)) {
            AbSemanticMetric metric = metricMapper.findByPid(tenantId, alert.getMetricPid());
            out.add(toDto(alert, metric));
        }
        return out;
    }

    public SemanticMetricAlertDTO update(String pid, SemanticMetricAlertRequest request) {
        Long tenantId = MetaContext.get().getTenantId();
        validateRequest(request);
        AbSemanticMetricAlert alert = requireAlert(tenantId, pid);
        AbSemanticMetric metric = requireMetric(tenantId, request.getMetricPid());
        applyRequest(alert, request, OffsetDateTime.now());
        alert.setUpdatedAt(OffsetDateTime.now());
        alertMapper.updateById(alert);
        return toDto(alert, metric);
    }

    public void delete(String pid) {
        Long tenantId = MetaContext.get().getTenantId();
        AbSemanticMetricAlert alert = requireAlert(tenantId, pid);
        alert.setDeletedFlag(true);
        alert.setUpdatedAt(OffsetDateTime.now());
        alertMapper.updateById(alert);
    }

    // ==================== Evaluation ====================

    /**
     * Evaluate one alert now. Returns a small result map
     * ({value, triggered, notified, silenced}) so the run-once endpoint and the
     * IT layer can assert on facts, not side effects.
     */
    public Map<String, Object> evaluateNow(String pid) {
        Long tenantId = MetaContext.get().getTenantId();
        AbSemanticMetricAlert alert = requireAlert(tenantId, pid);
        return evaluate(alert);
    }

    /**
     * Scheduler entry: every active alert across tenants, each evaluated inside
     * its own tenant context. One broken alert never stops the sweep.
     */
    @Scheduled(fixedDelay = 300_000)
    public void evaluateAll() {
        List<AbSemanticMetricAlert> alerts = alertMapper.listActiveAcrossTenants();
        for (AbSemanticMetricAlert alert : alerts) {
            try {
                evaluate(alert);
            } catch (Exception e) {
                log.warn("Semantic metric alert {} evaluation failed: {}", alert.getPid(), e.getMessage());
            }
        }
    }

    private Map<String, Object> evaluate(AbSemanticMetricAlert alert) {
        // Save/restore the caller's tenant context: evaluateNow runs on the
        // API caller's thread, where wiping the context would break every
        // subsequent request on it. Only the scheduler thread has no prior
        // context to restore.
        MetaContext previous = MetaContext.exists() ? MetaContext.get() : null;
        MetaContext.setContext(alert.getTenantId(), alert.getCreatedBy(),
                "semantic-alert", "semantic-alert-evaluator");
        try {
            AbSemanticMetric metric = metricMapper.findByPid(alert.getTenantId(), alert.getMetricPid());
            if (metric == null) {
                log.warn("Semantic metric alert {}: metric {} vanished; skipping",
                        alert.getPid(), alert.getMetricPid());
                return Map.of("skipped", "metric_missing");
            }
            AbSemanticModel model = modelMapper.findByPid(alert.getTenantId(), metric.getSemanticModelPid());
            if (model == null) {
                log.warn("Semantic metric alert {}: model {} vanished; skipping",
                        alert.getPid(), metric.getSemanticModelPid());
                return Map.of("skipped", "model_missing");
            }

            SemanticQueryRequest request = new SemanticQueryRequest();
            request.setMetrics(List.of(model.getCode() + "." + metric.getCode()));
            UserContext creator = new UserContext(alert.getCreatedBy(), alert.getTenantId(),
                    userAttributeService.getAttributes(alert.getTenantId(), alert.getCreatedBy()));
            SemanticQueryResponse response = queryService.executeQuery(request, creator);

            BigDecimal value = extractValue(response);
            if (value == null) {
                log.warn("Semantic metric alert {}: metric returned no value; skipping", alert.getPid());
                return Map.of("skipped", "no_value");
            }

            boolean triggered = compare(value, alert.getComparator(), alert.getThreshold());
            boolean notify = triggered && silenceWindowClosed(alert);
            if (notify) {
                notificationService.sendInApp(alert.getCreatedBy(),
                        "指标告警: " + alert.getName(),
                        metricLabel(metric) + " 当前值 " + value.toPlainString()
                                + " 已" + comparatorPhrase(alert.getComparator()) + "阈值 "
                                + alert.getThreshold().toPlainString(),
                        "semantic-alert", "semantic_metric_alert", alert.getPid());
            }

            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            alert.setLastEvaluatedAt(now);
            if (notify) alert.setLastTriggeredAt(now);
            alert.setUpdatedAt(now);
            alertMapper.updateById(alert);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("value", value);
            out.put("triggered", triggered);
            out.put("notified", notify);
            if (triggered && !notify) out.put("silenced", true);
            return out;
        } finally {
            if (previous != null) {
                MetaContext.setContext(previous.getTenantId(), previous.getUserId(),
                        previous.getUserPid(), previous.getUsername(), previous.getCurrentRoleIds());
            } else {
                MetaContext.clear();
            }
        }
    }

    // ==================== helpers ====================

    private BigDecimal extractValue(SemanticQueryResponse response) {
        if (response.getRows() == null || response.getRows().isEmpty()) return null;
        Map<String, Object> row = response.getRows().get(0);
        for (Object value : row.values()) {
            if (value == null) continue;
            if (value instanceof BigDecimal bd) return bd;
            if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
            try {
                return new BigDecimal(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean compare(BigDecimal value, String comparator, BigDecimal threshold) {
        int cmp = value.compareTo(threshold);
        return switch (comparator) {
            case "gt" -> cmp > 0;
            case "gte" -> cmp >= 0;
            case "lt" -> cmp < 0;
            case "lte" -> cmp <= 0;
            default -> throw new SemanticValidationException("SEMANTIC_ALERT_INVALID", "Unknown comparator: " + comparator);
        };
    }

    private boolean silenceWindowClosed(AbSemanticMetricAlert alert) {
        if (alert.getLastTriggeredAt() == null) return true;
        int silence = alert.getSilenceMinutes() == null ? 60 : alert.getSilenceMinutes();
        return alert.getLastTriggeredAt().plusMinutes(silence)
                .isBefore(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private void validateRequest(SemanticMetricAlertRequest request) {
        if (!COMPARATORS.contains(request.getComparator())) {
            throw new SemanticValidationException("SEMANTIC_ALERT_INVALID",
                    "comparator must be one of " + COMPARATORS);
        }
        if (request.getThreshold() == null) {
            throw new SemanticValidationException("SEMANTIC_ALERT_INVALID", "threshold is required");
        }
        if (request.getAlertStatus() != null && !STATUSES.contains(request.getAlertStatus())) {
            throw new SemanticValidationException("SEMANTIC_ALERT_INVALID", "alertStatus must be active or paused");
        }
        if (request.getSilenceMinutes() != null && request.getSilenceMinutes() < 0) {
            throw new SemanticValidationException("SEMANTIC_ALERT_INVALID", "silenceMinutes must be >= 0");
        }
    }

    private void applyRequest(AbSemanticMetricAlert alert, SemanticMetricAlertRequest request,
                              OffsetDateTime now) {
        alert.setName(request.getName());
        alert.setMetricPid(request.getMetricPid());
        alert.setComparator(request.getComparator());
        alert.setThreshold(request.getThreshold());
        alert.setSilenceMinutes(request.getSilenceMinutes() == null ? 60 : request.getSilenceMinutes());
        if (request.getAlertStatus() != null) alert.setAlertStatus(request.getAlertStatus());
        alert.setUpdatedAt(now);
    }

    private AbSemanticMetric requireMetric(Long tenantId, String metricPid) {
        AbSemanticMetric metric = metricMapper.findByPid(tenantId, metricPid);
        if (metric == null) {
            throw new SemanticValidationException("SEMANTIC_ALERT_METRIC_MISSING", "Metric not found: " + metricPid);
        }
        return metric;
    }

    private AbSemanticMetricAlert requireAlert(Long tenantId, String pid) {
        AbSemanticMetricAlert alert = alertMapper.findByPid(tenantId, pid);
        if (alert == null) {
            throw new SemanticValidationException("SEMANTIC_ALERT_MISSING", "Alert not found: " + pid);
        }
        return alert;
    }

    private String metricLabel(AbSemanticMetric metric) {
        String label = metric.getLabelI18n();
        if (label != null && label.startsWith("{")) {
            // labelI18n is a JSON map ({...}); show the zh-CN value when present.
            int zh = label.indexOf("zh-CN");
            if (zh >= 0) {
                int colon = label.indexOf(':', zh);
                int start = label.indexOf('"', colon);
                int end = label.indexOf('"', start + 1);
                if (start >= 0 && end > start) return label.substring(start + 1, end);
            }
        }
        return metric.getCode();
    }

    private String comparatorPhrase(String comparator) {
        return switch (comparator) {
            case "gt" -> "高于";
            case "gte" -> "不低于";
            case "lt" -> "低于";
            case "lte" -> "不高于";
            default -> "越过";
        };
    }

    private SemanticMetricAlertDTO toDto(AbSemanticMetricAlert alert, AbSemanticMetric metric) {
        SemanticMetricAlertDTO.SemanticMetricAlertDTOBuilder builder = SemanticMetricAlertDTO.builder()
                .pid(alert.getPid())
                .name(alert.getName())
                .metricPid(alert.getMetricPid())
                .comparator(alert.getComparator())
                .threshold(alert.getThreshold())
                .silenceMinutes(alert.getSilenceMinutes())
                .alertStatus(alert.getAlertStatus())
                .lastTriggeredAt(alert.getLastTriggeredAt())
                .lastEvaluatedAt(alert.getLastEvaluatedAt())
                .createdBy(alert.getCreatedBy())
                .createdAt(alert.getCreatedAt())
                .updatedAt(alert.getUpdatedAt());
        if (metric != null) {
            builder.metricCode(metric.getCode()).metricLabel(metric.getLabelI18n());
        }
        return builder.build();
    }
}
