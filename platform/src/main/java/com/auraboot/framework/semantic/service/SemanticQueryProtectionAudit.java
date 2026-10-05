package com.auraboot.framework.semantic.service;

import com.auraboot.framework.meta.dto.AuditTrailEvent;
import com.auraboot.framework.meta.service.impl.AuditTrailService;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persist admission evidence independently of a caller's preaggregation transaction. */
@Service
@RequiredArgsConstructor
public class SemanticQueryProtectionAudit {
    private final AuditTrailService audits;
    private final ObjectMapper json;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String queryId, String entry, String semanticModelCode, String sqlFingerprint,
                       SemanticFieldProtection.Plan plan, UserContext user) {
        var metadata = json.createObjectNode();
        metadata.put("auditHashVersion", 2);
        metadata.put("format", "auraboot.semantic.protection.v1");
        metadata.put("phase", "admission");
        metadata.put("semanticModelCode", semanticModelCode);
        metadata.put("sqlFingerprint", sqlFingerprint);
        metadata.set("protectionPlan", json.valueToTree(plan));
        // No SQL, bind values, request filter values or result rows enter this snapshot.
        // Persistence failure propagates before SQL or compiled results are returned.
        audits.recordAudit(AuditTrailEvent.builder().tenantId(user.tenantId()).actorId(user.userId())
                .eventType("SEMANTIC_QUERY_PROTECTION").entityType("semantic_query").entityPid(queryId)
                .operationType(entry).metadata(metadata).build());
    }
}
