package com.auraboot.framework.permission.mapper;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.permission.entity.RecordShare;
import com.auraboot.framework.permission.service.RecordShareService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Autowired
    private RecordShareService recordShareService;

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

        assertThat(mapper.hasDirectMemberShareByRecordPid(
                TENANT_ID, RESOURCE, "rec-read", null, MEMBER_PID, "read", now)).isTrue();
        assertThat(mapper.hasDirectMemberShareByRecordPid(
                TENANT_ID, RESOURCE, "rec-read", null, MEMBER_PID, "update", now)).isFalse();
        assertThat(mapper.hasDirectMemberShareByRecordPid(
                TENANT_ID, RESOURCE, "rec-read", MEMBER_ID, "unmatched-pid", "read", now)).isTrue();
        assertThat(mapper.hasDirectMemberShareByRecordPid(
                TENANT_ID, RESOURCE, "rec-read", MEMBER_ID + 1, "unmatched-pid", "read", now)).isFalse();
        assertThat(mapper.countByRecordAndUser(
                TENANT_ID, RESOURCE, 101L, MEMBER_ID, "read", now))
                .isOne();
        assertThat(mapper.countByRecordAndUser(
                TENANT_ID, RESOURCE, 101L, MEMBER_ID, "delete", now))
                .isZero();
    }

    @Test
    void combinedKeysKeepLegacyAndPublicSubjectRulesIndependent() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant later = now.plus(1, ChronoUnit.HOURS);
        insert(TENANT_ID, 301L, "legacy-pair", "member", MEMBER_ID, null, "read", later);
        insert(TENANT_ID, 302L, "public-only", "member", MEMBER_ID + 1, MEMBER_PID, "read, update", later);
        insert(TENANT_ID, 303L, "role-pair", "role", ROLE_ID, null, "read", later);
        insert(TENANT_ID, 304L, "expired", "member", MEMBER_ID, MEMBER_PID, "read", now);
        insert(OTHER_TENANT_ID, 305L, "cross-tenant", "member", MEMBER_ID, MEMBER_PID, "read", later);

        assertThat(mapper.findSharedRecordKeys(TENANT_ID, RESOURCE, MEMBER_ID, MEMBER_PID,
                List.of(ROLE_ID), "read", now)).containsExactlyInAnyOrder(
                new RecordShareMapper.SharedRecordKey(301L, "legacy-pair"),
                new RecordShareMapper.SharedRecordKey(null, "public-only"),
                new RecordShareMapper.SharedRecordKey(303L, "role-pair"));
        assertThat(mapper.findSharedRecordKeys(TENANT_ID, RESOURCE, MEMBER_ID, MEMBER_PID,
                List.of(), "update", now)).containsExactly(new RecordShareMapper.SharedRecordKey(null, "public-only"));
        assertThat(mapper.findSharedRecordKeys(TENANT_ID, RESOURCE, MEMBER_ID, null,
                List.of(), "read", now)).containsExactly(new RecordShareMapper.SharedRecordKey(301L, "legacy-pair"));
    }

    @Test
    @DisplayName("public-PID collaborator upsert upgrades the existing relationship")
    void upsertByPublicPidsUpdatesPermissionWithoutDuplicatingShare() {
        RecordShare readOnly = publicShare("share-original", "rec-upsert", "read");
        mapper.upsertByPublicPids(readOnly);

        RecordShare collaborator = publicShare("share-replacement", "rec-upsert", "read,update");
        mapper.upsertByPublicPids(collaborator);

        List<RecordShare> shares = mapper.findByRecordPid(
                TENANT_ID, RESOURCE, "rec-upsert", Instant.now());
        assertThat(shares).singleElement().satisfies(share -> {
            assertThat(share.getPid()).isEqualTo("share-original");
            assertThat(share.getPermissionMask()).isEqualTo("read,update");
            assertThat(share.getSubjectPid()).isEqualTo(MEMBER_PID);
        });
    }

    @Test
    @DisplayName("expired relationship remains manageable but never grants access")
    void expiredRelationshipCanBeListedAndRenewedWithoutGrantingAccessUntilRenewal() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        RecordShare expired = publicShare("share-expired-renewable", "rec-renew", "read");
        expired.setExpiresAt(now.minus(1, ChronoUnit.HOURS));
        mapper.upsertByPublicPids(expired);

        assertThat(mapper.findByRecordPid(TENANT_ID, RESOURCE, "rec-renew", now)).isEmpty();
        assertThat(mapper.hasDirectMemberShareByRecordPid(
                TENANT_ID, RESOURCE, "rec-renew", null, MEMBER_PID, "read", now)).isFalse();
        RecordShare persistedExpired = mapper.findByRecordPidForManagement(
                        TENANT_ID, RESOURCE, "rec-renew", now)
                .getFirst();
        assertThat(persistedExpired.getPid()).isEqualTo("share-expired-renewable");

        Instant renewedUntil = now.plus(30, ChronoUnit.DAYS);
        assertThat(mapper.updatePolicyByPidInTenant(
                TENANT_ID,
                "share-expired-renewable",
                "read,update",
                renewedUntil))
                .isOne();
        assertThat(mapper.hasDirectMemberShareByRecordPid(
                TENANT_ID, RESOURCE, "rec-renew", null, MEMBER_PID, "update", now)).isTrue();
        assertThat(mapper.findByRecordPidForManagement(TENANT_ID, RESOURCE, "rec-renew", now))
                .singleElement()
                .satisfies(share -> {
                    assertThat(share.getCreatedAt()).isEqualTo(persistedExpired.getCreatedAt());
                    assertThat(share.getCreatedBy()).isEqualTo(persistedExpired.getCreatedBy());
                });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void batchRemovalRollsBackWhenAnyShareIsOutsideTheTenant() {
        RecordShare first = publicShare(UniqueIdGenerator.generate(), UniqueIdGenerator.generate(), "read");
        RecordShare second = publicShare(UniqueIdGenerator.generate(), UniqueIdGenerator.generate(), "read");
        RecordShare otherTenant = publicShare(
                UniqueIdGenerator.generate(), UniqueIdGenerator.generate(), "read");
        otherTenant.setTenantId(OTHER_TENANT_ID);

        try {
            mapper.upsertByPublicPids(first);
            mapper.upsertByPublicPids(second);
            mapper.upsertByPublicPids(otherTenant);

            assertThatThrownBy(() -> recordShareService.removeByPids(
                    TENANT_ID, List.of(first.getPid(), otherTenant.getPid(), second.getPid())))
                    .isInstanceOf(RootUnCheckedException.class)
                    .hasMessageContaining("Share not found");
            assertThat(mapper.findByPidInTenant(TENANT_ID, first.getPid())).isNotNull();
            assertThat(mapper.findByPidInTenant(TENANT_ID, second.getPid())).isNotNull();
            assertThat(mapper.findByPidInTenant(OTHER_TENANT_ID, otherTenant.getPid())).isNotNull();

            recordShareService.removeByPids(TENANT_ID, List.of(first.getPid(), second.getPid()));
            assertThat(mapper.findByPidInTenant(TENANT_ID, first.getPid())).isNull();
            assertThat(mapper.findByPidInTenant(TENANT_ID, second.getPid())).isNull();
            assertThat(mapper.findByPidInTenant(OTHER_TENANT_ID, otherTenant.getPid())).isNotNull();
        } finally {
            for (RecordShare share : List.of(first, second, otherTenant)) {
                mapper.deleteByPidInTenant(share.getTenantId(), share.getPid());
            }
        }
    }

    private RecordShare publicShare(String sharePid, String recordPid, String permissionMask) {
        RecordShare share = new RecordShare();
        share.setPid(sharePid);
        share.setTenantId(TENANT_ID);
        share.setResourceCode(RESOURCE);
        share.setRecordPid(recordPid);
        share.setSubjectType("member");
        share.setSubjectPid(MEMBER_PID);
        share.setPermissionMask(permissionMask);
        share.setCreatedAt(Instant.now());
        share.setCreatedBy(MEMBER_ID);
        return share;
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
