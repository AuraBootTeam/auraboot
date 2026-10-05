package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.AuditTrailEvent;
import com.auraboot.framework.meta.entity.AuditTrail;
import com.auraboot.framework.meta.mapper.AuditTrailMapper;
import com.auraboot.framework.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuditTrailCanonicalHashTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AuditTrailMapper mapper = mock(AuditTrailMapper.class);
    private final AuditTrailService service = new AuditTrailService(mapper, mock(UserMapper.class));

    @Test
    void versionTwoIgnoresObjectKeyOrderAtEveryDepth() throws Exception {
        AuditTrail record = record(2);
        record.setMetadata(json.readTree("{\"z\":{\"b\":2,\"a\":1},\"a\":null}"));
        String first = service.buildCanonicalString(record);
        record.setMetadata(json.readTree("{\"a\":null,\"z\":{\"a\":1,\"b\":2}}"));
        assertThat(service.buildCanonicalString(record)).isEqualTo(first);
    }

    @Test
    void versionTwoNormalizesEquivalentNumericRepresentations() throws Exception {
        AuditTrail record = record(2);
        record.setAfterSnapshot(json.readTree("{\"value\":1.0,\"nested\":[2.00]}"));
        String first = service.buildCanonicalString(record);
        record.setAfterSnapshot(json.readTree("{\"nested\":[2],\"value\":1}"));
        assertThat(service.buildCanonicalString(record)).isEqualTo(first);
    }

    @Test
    void arrayOrderAndFieldMutationRemainDetectable() throws Exception {
        AuditTrail record = record(2);
        record.setAfterSnapshot(json.readTree("[1,2]"));
        String first = service.buildCanonicalString(record);
        record.setAfterSnapshot(json.readTree("[2,1]"));
        assertThat(service.buildCanonicalString(record)).isNotEqualTo(first);
    }

    @Test
    void legacyFormatIsNotReinterpretedAsVersionTwo() {
        AuditTrail legacy = record(1);
        String original = "11|1|command_executed|fixture||row_pid|fixture:approve|UPDATE|17||2026-10-03T10:00:00.123456Z||||";
        assertThat(service.buildCanonicalString(legacy)).isEqualTo(original);
        legacy.setHashVersion(2);
        assertThat(service.buildCanonicalString(legacy)).isNotEqualTo(original);
    }

    @Test
    void unsupportedHashVersionFailsClosed() {
        assertThatThrownBy(() -> service.buildCanonicalString(record(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writerUsesVersionTwoAndMicrosecondsBeforeComputingHash() {
        AuditTrail saved = service.doRecordAudit(AuditTrailEvent.builder()
                .tenantId(11L).actorId(17L).eventType("command_executed")
                .entityType("fixture").entityPid("row_pid").commandCode("fixture:approve")
                .operationType("UPDATE").build());
        assertThat(saved.getHashVersion()).isEqualTo(2);
        assertThat(saved.getTimestamp().getNano() % 1000).isZero();
        assertThat(saved.getRecordHash()).isEqualTo(service.computeSha256(
                service.buildCanonicalString(saved) + saved.getPreviousHash()));
    }

    @Test
    void verificationStillRejectsStoredContentTampering() {
        AuditTrail record = record(2);
        record.setRecordHash(service.computeSha256(service.buildCanonicalString(record) + "genesis"));
        when(mapper.getBySequenceRange(11L, 1L, 1L)).thenReturn(List.of(record));
        assertThat(service.verifyChainIntegrity(11L, 1L, 1L).isValid()).isTrue();
        record.setActorName("altered actor");
        assertThat(service.verifyChainIntegrity(11L, 1L, 1L).isValid()).isFalse();
    }

    private AuditTrail record(int version) {
        AuditTrail record = new AuditTrail();
        record.setHashVersion(version);record.setTenantId(11L);record.setSequenceNo(1L);
        record.setEventType("command_executed");record.setEntityType("fixture");
        record.setEntityPid("row_pid");record.setCommandCode("fixture:approve");
        record.setOperationType("UPDATE");record.setActorId(17L);
        record.setTimestamp(Instant.parse("2026-10-03T10:00:00.123456Z"));
        record.setPreviousHash("genesis");return record;
    }
}
