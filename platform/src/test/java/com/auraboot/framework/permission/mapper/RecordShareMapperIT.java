package com.auraboot.framework.permission.mapper;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-PostgreSQL contract for record-share tenant, expiry, subject and permission-mask filters.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
class RecordShareMapperIT {

    private static final long TENANT_ID = 994_008_001L;
    private static final long OTHER_TENANT_ID = 994_008_002L;
    private static final long MEMBER_ID = 994_008_010L;
    private static final long ROLE_ID = 994_008_020L;
    private static final String MEMBER_PID = "crm_share_member_20260808";
    private static final String RESOURCE = "crm_opportunity_common";

    @Autowired
    private RecordShareMapper mapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("shared record queries enforce action mask, expiry and tenant isolation")
    void filtersSharedRecordsByActionExpiryAndTenant() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant future = now.plus(1, ChronoUnit.HOURS);
        Instant past = now.minus(1, ChronoUnit.HOURS);

        insert(TENANT_ID, 101L, "rec-read", "member", MEMBER_ID, MEMBER_PID, "read", future);
        insert(TENANT_ID, 102L, "rec-update", "member", MEMBER_ID, MEMBER_PID, "update", future);
        insert(TENANT_ID, null, "rec-multi", "member", null, MEMBER_PID, "read, update", future);
        insert(TENANT_ID, null, "rec-role", "role", ROLE_ID, null, "read", future);
        insert(TENANT_ID, 103L, "rec-expired", "member", MEMBER_ID, MEMBER_PID, "read", past);
        insert(OTHER_TENANT_ID, 104L, "rec-cross-tenant", "member", MEMBER_ID, MEMBER_PID, "read", future);

        assertThat(mapper.findSharedRecordIds(
                TENANT_ID, RESOURCE, MEMBER_ID, List.of(ROLE_ID), "read", now))
                .containsExactly(101L);
        assertThat(mapper.findSharedRecordIds(
                TENANT_ID, RESOURCE, MEMBER_ID, List.of(ROLE_ID), "update", now))
                .containsExactly(102L);

        assertThat(mapper.findSharedRecordPids(
                TENANT_ID, RESOURCE, MEMBER_ID, MEMBER_PID, List.of(ROLE_ID), "read", now))
                .containsExactlyInAnyOrder("rec-read", "rec-multi", "rec-role");
        assertThat(mapper.findSharedRecordPids(
                TENANT_ID, RESOURCE, MEMBER_ID, MEMBER_PID, List.of(ROLE_ID), "update", now))
                .containsExactlyInAnyOrder("rec-update", "rec-multi");

        assertThat(mapper.countByRecordPidAndSubjectPid(
                TENANT_ID, RESOURCE, "rec-read", "member", MEMBER_PID, "read", now))
                .isOne();
        assertThat(mapper.countByRecordPidAndSubjectPid(
                TENANT_ID, RESOURCE, "rec-read", "member", MEMBER_PID, "update", now))
                .isZero();
        assertThat(mapper.countByRecordAndUser(
                TENANT_ID, RESOURCE, 101L, MEMBER_ID, "read", now))
                .isOne();
        assertThat(mapper.countByRecordAndUser(
                TENANT_ID, RESOURCE, 101L, MEMBER_ID, "delete", now))
                .isZero();
    }

    private void insert(
            long tenantId,
            Long recordId,
            String recordPid,
            String subjectType,
            Long subjectId,
            String subjectPid,
            String permissionMask,
            Instant expiresAt) {
        jdbcTemplate.update(
                """
                INSERT INTO ab_record_share
                    (pid, tenant_id, resource_code, record_id, record_pid, subject_type,
                     subject_id, subject_pid, permission_mask, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """,
                UniqueIdGenerator.generate(),
                tenantId,
                RESOURCE,
                recordId,
                recordPid,
                subjectType,
                subjectId,
                subjectPid,
                permissionMask,
                Timestamp.from(expiresAt));
    }
}
