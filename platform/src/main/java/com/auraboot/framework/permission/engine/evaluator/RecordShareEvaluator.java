package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.service.RecordShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * ReBAC evaluator — checks record sharing exceptions.
 *
 * <p>If a record is shared with the member (directly or via role),
 * this evaluator returns ALLOW, which bypasses DataScope restrictions.
 * Otherwise it returns NOT_APPLICABLE (does not deny, just has no opinion).
 */
@Component
@RequiredArgsConstructor
public class RecordShareEvaluator {

    private static final String NAME = "RecordShare";

    private final RecordShareService recordShareService;

    /**
     * Evaluate whether the member has access to the record via sharing.
     *
     * @param memberId member (user) ID
     * @param resource resource identifier (model code)
     * @param action   action identifier
     * @param record   the target record (Map with "pid"/"recordPid" or legacy "id" key)
     * @return evaluation step: ALLOW if shared, NOT_APPLICABLE otherwise
     */
    @SuppressWarnings("unchecked")
    public EvaluationStep evaluate(Long memberId, String resource, String action, Object record) {
        if (record == null) {
            return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE,
                    "No record provided — skipping share check");
        }

        String recordPid = extractRecordPid(record);
        if (StringUtils.hasText(recordPid)) {
            Long tenantId = MetaContext.getCurrentTenantId();
            boolean shared = recordShareService.isSharedByPid(
                    tenantId, resource, recordPid, memberId, MetaContext.getCurrentUserPid());
            if (shared) {
                return new EvaluationStep(NAME, EvaluationVerdict.ALLOW,
                        "Record is shared with member — access granted (bypasses DataScope)");
            }
            return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE,
                    "Record is not shared with member — no override");
        }

        Long recordId = extractRecordId(record);
        if (recordId == null) {
            return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE,
                    "Record has no 'pid' or numeric 'id' field — skipping share check");
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        boolean shared = recordShareService.isShared(tenantId, resource, recordId, memberId);

        if (shared) {
            return new EvaluationStep(NAME, EvaluationVerdict.ALLOW,
                    "Record is shared with member — access granted (bypasses DataScope)");
        }

        return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE,
                "Record is not shared with member — no override");
    }

    /**
     * Extract the record ID from a record object (expected to be a Map).
     */
    private Long extractRecordId(Object record) {
        if (!(record instanceof Map<?, ?> recordMap)) {
            return null;
        }
        Object idObj = recordMap.get("id");
        if (idObj == null) {
            return null;
        }
        if (idObj instanceof Number) {
            return ((Number) idObj).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(idObj));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Extract the public record PID from a record object (expected to be a Map).
     */
    private String extractRecordPid(Object record) {
        if (!(record instanceof Map<?, ?> recordMap)) {
            return null;
        }
        Object recordPid = recordMap.get("recordPid");
        if (recordPid == null) {
            recordPid = recordMap.get("pid");
        }
        if (recordPid == null || !StringUtils.hasText(String.valueOf(recordPid))) {
            return null;
        }
        return String.valueOf(recordPid).trim();
    }
}
