package com.auraboot.framework.semantic.service;

import com.auraboot.framework.meta.entity.AuditTrail;
import com.auraboot.framework.meta.mapper.AuditTrailMapper;
import com.auraboot.framework.meta.service.impl.AuditTrailService;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic production writer/hash path; PostgreSQL and transaction propagation need IT. */
class SemanticQueryProtectionAuditTest {
    private final ObjectMapper json = new ObjectMapper();
    private AuditTrailMapper mapper;
    private AuditTrailService trails;
    private SemanticQueryProtectionAudit writer;
    private final UserContext user = new UserContext(42L, 1L, null);
    private final SemanticFieldProtection.Plan denied = new SemanticFieldProtection.Plan("source_record",
            List.of("amount", "tenant_id"), List.of(new SemanticFieldProtection.Protection(
                    "sensitive", "amount", "column-policy", "HIDE")),
            SemanticFieldProtection.Verdict.DENY, SemanticFieldProtection.Reason.PROTECTED_SOURCE_COLUMN);

    @BeforeEach void setup() {
        mapper = mock(AuditTrailMapper.class);
        trails = new AuditTrailService(mapper, mock(UserMapper.class));
        writer = new SemanticQueryProtectionAudit(trails, json);
    }

    private AuditTrail write(SemanticFieldProtection.Plan plan) {
        writer.record("query-pid", "QUERY", "g", "fingerprint", plan, user);
        var capture = org.mockito.ArgumentCaptor.forClass(AuditTrail.class);
        verify(mapper).insert(capture.capture()); return capture.getValue();
    }

    @Test void rejectionWritesExplicitPlanAndIdentityWithoutRawData() {
        var row = write(denied);
        assertThat(row.getTenantId()).isEqualTo(1L);
        assertThat(row.getActorId()).isEqualTo(42L);
        assertThat(row.getEntityPid()).isEqualTo("query-pid");
        assertThat(row.getEntityType()).isEqualTo("semantic_query");
        assertThat(row.getEventType()).isEqualTo("SEMANTIC_QUERY_PROTECTION");
        assertThat(row.getOperationType()).isEqualTo("QUERY");
        var metadata = row.getMetadata();
        assertThat(metadata.path("phase").asText()).isEqualTo("admission");
        assertThat(metadata.path("semanticModelCode").asText()).isEqualTo("g");
        assertThat(metadata.path("sqlFingerprint").asText()).isEqualTo("fingerprint");
        assertThat(metadata.path("protectionPlan")).isEqualTo(json.valueToTree(denied));
        assertThat(metadata.has("sql") || metadata.has("params") || metadata.has("rows") || metadata.has("filters"))
                .isFalse();
        assertThat(row.getRecordHash()).matches("[a-f0-9]{64}");
    }

    @Test void allowedAdmissionStillRecordsTheProtectionDecision() {
        var allowed = new SemanticFieldProtection.Plan("source_record", List.of("tenant_id"), List.of(),
                SemanticFieldProtection.Verdict.ALLOW, SemanticFieldProtection.Reason.NONE);
        assertThat(write(allowed).getMetadata().path("protectionPlan")).isEqualTo(json.valueToTree(allowed));
    }

    @Test void persistenceFailurePropagatesWithoutFalseSuccessfulAudit() {
        doThrow(new DataAccessResourceFailureException("storage down")).when(mapper).insert(any(AuditTrail.class));
        assertThatThrownBy(() -> writer.record("query", "QUERY", "g", "fingerprint", denied, user))
                .isInstanceOf(DataAccessResourceFailureException.class).hasMessage("storage down");
    }

    @Test void version2SurvivesJsonObjectKeyReordering() {
        var row = write(denied);
        row.setMetadata(reordered(row.getMetadata()));
        when(mapper.getBySequenceRange(1L, 1L, 1L)).thenReturn(List.of(row));
        var result = trails.verifyChainIntegrity(1L, 1L, 1L);
        assertThat(result.isValid()).isTrue();
        assertThat(result.getRecordsVerified()).isEqualTo(1L);
    }

    @Test void alteringPlanDecisionBreaksVersion2Integrity() {
        var row = write(denied);
        ((com.fasterxml.jackson.databind.node.ObjectNode) row.getMetadata().path("protectionPlan"))
                .put("verdict", "ALLOW");
        when(mapper.getBySequenceRange(1L, 1L, 1L)).thenReturn(List.of(row));
        var result = trails.verifyChainIntegrity(1L, 1L, 1L);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getBrokenAtSequence()).isEqualTo(1L);
    }

    private JsonNode reordered(JsonNode node) {
        if (node.isObject()) {
            var result = json.createObjectNode();
            List<String> keys = new ArrayList<>(); node.fieldNames().forEachRemaining(keys::add);
            Collections.reverse(keys); keys.forEach(key -> result.set(key, reordered(node.get(key))));
            return result;
        }
        if (node.isArray()) {
            var result = json.createArrayNode(); node.forEach(item -> result.add(reordered(item))); return result;
        }
        return node.deepCopy();
    }
}
