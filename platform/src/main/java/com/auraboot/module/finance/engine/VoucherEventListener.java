package com.auraboot.module.finance.engine;

import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Listens for CommandCompletedEvent, matches against active voucher templates,
 * and triggers automatic voucher (journal entry) generation.
 *
 * Runs within the same transaction as the command execution.
 * Each template processing is wrapped in try-catch to prevent one failure
 * from blocking other template matches.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoucherEventListener {

    private final DynamicDataService dynamicDataService;
    private final VoucherTemplateResolver templateResolver;
    private final VoucherGenerationService voucherGenerationService;

    @EventListener
    public void onCommandCompleted(CommandCompletedEvent event) {
        // 1. Skip finance namespace events to prevent infinite loops
        if (event.getCommandCode() != null && event.getCommandCode().startsWith("fac:")) {
            return;
        }

        // 2. Build event pattern: "{commandCode}:{operationType}"
        String eventPattern = event.getCommandCode() + ":" + event.getOperationType();

        // 3. Query active templates matching this event pattern
        List<Map<String, Object>> templates = queryMatchingTemplates(eventPattern);

        if (templates.isEmpty()) {
            return;
        }

        log.debug("Found {} voucher templates matching event pattern '{}'",
            templates.size(), eventPattern);

        // 4. Process each matching template
        for (Map<String, Object> template : templates) {
            processTemplate(template, event, eventPattern);
        }
    }

    private List<Map<String, Object>> queryMatchingTemplates(String eventPattern) {
        try {
            DynamicQueryRequest query = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(100)
                .conditions(List.of(
                    QueryCondition.builder()
                        .fieldName("fac_vt_event_pattern")
                        .operator(QueryCondition.Operator.EQ)
                        .value(eventPattern)
                        .build(),
                    QueryCondition.builder()
                        .fieldName("fac_vt_status")
                        .operator(QueryCondition.Operator.EQ)
                        .value("active")
                        .build()
                ))
                .build();

            PaginationResult<Map<String, Object>> result =
                dynamicDataService.list("fac_voucher_template", query);

            return result != null && result.getRecords() != null ? result.getRecords() : List.of();
        } catch (Exception e) {
            log.warn("Failed to query voucher templates for pattern '{}': {}",
                eventPattern, e.getMessage());
            return List.of();
        }
    }

    private void processTemplate(Map<String, Object> template,
                                  CommandCompletedEvent event, String eventPattern) {
        String templateCode = (String) template.get("fac_vt_code");
        try {
            // Evaluate SpEL condition (if any)
            String condition = (String) template.get("fac_vt_condition");
            if (condition != null && !condition.isBlank()) {
                boolean passes = templateResolver.evaluateCondition(condition, event.getPayload());
                if (!passes) {
                    log.debug("Template {} condition not met, skipping", templateCode);
                    return;
                }
            }

            // Get template lines
            String templateId = resolveId(template);
            List<Map<String, Object>> lines = queryTemplateLines(templateId);

            // Generate voucher
            String jeId = voucherGenerationService.generateVoucher(
                template, lines, event.getPayload(),
                event.getModelCode(), event.getRecordId(),
                extractSourceCode(event.getPayload()));

            if (jeId != null) {
                log.info("Generated voucher {} from template {} for event {}",
                    jeId, templateCode, eventPattern);
            }
        } catch (Exception e) {
            log.error("Failed to generate voucher from template {} for event {}: {}",
                templateCode, eventPattern, e.getMessage(), e);
            // Don't rethrow — one template failure shouldn't block others
        }
    }

    private List<Map<String, Object>> queryTemplateLines(String templateId) {
        DynamicQueryRequest query = DynamicQueryRequest.builder()
            .pageNum(1)
            .pageSize(100)
            .conditions(List.of(
                QueryCondition.builder()
                    .fieldName("fac_vtl_template_id")
                    .operator(QueryCondition.Operator.EQ)
                    .value(templateId)
                    .build()
            ))
            .build();

        PaginationResult<Map<String, Object>> result =
            dynamicDataService.list("fac_voucher_template_line", query);

        return result != null && result.getRecords() != null ? result.getRecords() : List.of();
    }

    private String extractSourceCode(Map<String, Object> payload) {
        if (payload == null) return null;
        // Try common code field patterns
        for (String key : List.of("pe_so_code", "pe_po_code", "pe_wh_out_code", "pe_wh_in_code", "code")) {
            Object val = payload.get(key);
            if (val != null) return val.toString();
        }
        return null;
    }

    private static String resolveId(Map<String, Object> record) {
        if (record == null) return null;
        Object pid = record.get("pid");
        if (pid != null) return pid.toString();
        Object id = record.get("id");
        return id != null ? id.toString() : null;
    }
}
