package com.auraboot.framework.p1demo;

import com.auraboot.framework.application.tenant.MetaContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P1 vertical-slice REST endpoints for the wd_leave_request AI flow.
 *
 * Three endpoints validate three pieces of the §4 / §5 design:
 *   POST /api/wd-leave-request/ai-fill
 *     — grounding LLM call + annotation upsert. Returns parsed fields.
 *   POST /api/wd-leave-request/safety-check
 *     — local safety rule (days &gt; 5) per §4.4 condition DSL `step.cost`-style
 *       check, recorded into safety_triggers. Returns escalation decision.
 *   GET  /api/wd-leave-request/{id}/ai-annotation
 *     — annotation read for detail / list pages.
 *
 * P2 platformization will replace this controller with the SafetyValveService /
 * ExecutionLogService SDK and the SafetyConditionEvaluator JSON DSL. Do not
 * extend this class — replace it.
 */
@Slf4j
@RestController
@RequestMapping("/api/wd-leave-request")
@RequiredArgsConstructor
public class WdLeaveAiController {

    private static final String TARGET_MODEL_CODE = "wd_leave_request";
    private static final int DAYS_THRESHOLD = 5;
    private static final String RULE_DAYS_OVER_LIMIT = "wd_days_over_5";

    private final WdLeaveAiFillService aiFillService;
    private final AcpAiAnnotationRepository annotationRepository;

    @PostMapping("/ai-fill")
    public ResponseEntity<AiFillResponse> aiFill(@RequestBody AiFillRequest req) {
        if (req == null || req.nlInput() == null || req.nlInput().isBlank()) {
            return ResponseEntity.badRequest().body(AiFillResponse.error("nlInput is required"));
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        String currentDate = req.currentDate() != null ? req.currentDate() : LocalDate.now().toString();

        WdLeaveAiFillService.AiFillResult result =
                aiFillService.extractFields(req.nlInput(), currentDate, tenantId);

        Long targetId = req.targetId() != null ? req.targetId() : -1L;
        Long annotationId = annotationRepository.upsertGrounding(
                tenantId, TARGET_MODEL_CODE, targetId, result.turnId(),
                req.nlInput(), result.fields());

        return ResponseEntity.ok(new AiFillResponse(
                result.turnId(), result.fields(), annotationId, result.totalTokens(),
                result.totalDollars(), null));
    }

    @PostMapping("/safety-check")
    public ResponseEntity<SafetyCheckResponse> safetyCheck(@RequestBody SafetyCheckRequest req) {
        List<String> triggers = new ArrayList<>();
        if (req != null && req.days() != null && req.days() > DAYS_THRESHOLD) {
            triggers.add(RULE_DAYS_OVER_LIMIT);
        }
        boolean requiresEscalation = !triggers.isEmpty();

        if (req != null && req.targetId() != null && req.turnId() != null) {
            Long tenantId = MetaContext.getCurrentTenantId();
            Map<String, Object> existing = annotationRepository.findByTarget(
                    tenantId, TARGET_MODEL_CODE, req.targetId());
            if (existing != null) {
                annotationRepository.recordSafetyTrigger(
                        ((Number) existing.get("id")).longValue(), triggers);
            }
        }

        return ResponseEntity.ok(new SafetyCheckResponse(triggers, requiresEscalation,
                requiresEscalation ? "天数超过 " + DAYS_THRESHOLD + " 天,提交后将升级为二级审批" : null));
    }

    @GetMapping("/{id}/ai-annotation")
    public ResponseEntity<Map<String, Object>> getAnnotation(@PathVariable("id") Long id) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, Object> row = annotationRepository.findByTarget(tenantId, TARGET_MODEL_CODE, id);
        if (row == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(row);
    }

    public record AiFillRequest(String nlInput, String currentDate, Long targetId) {}

    public record AiFillResponse(String turnId, Map<String, Object> fields, Long annotationId,
                                  long totalTokens, double totalDollars, String error) {
        public static AiFillResponse error(String message) {
            return new AiFillResponse(null, Map.of(), null, 0, 0.0, message);
        }
    }

    public record SafetyCheckRequest(Integer days, Long targetId, String turnId) {}

    public record SafetyCheckResponse(List<String> triggers, boolean requiresEscalation, String message) {}
}
