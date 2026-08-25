package com.auraboot.framework.meta.mapper;

import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicSqlProviderAtomicBatchClaimTest {

    @Test
    void buildsOneTenantGuardedSkipLockedUpdateReturningStatement() {
        Map<String, Object> parameters = parameters();

        String sql = DynamicSqlProvider.atomicBatchClaimReturning(parameters);

        assertThat(sql)
                .startsWith("WITH candidates AS (SELECT pid FROM mt_job WHERE tenant_id = #{tenantId}")
                .contains("AND lane = #{exactFilters.lane}")
                .contains("AND status IN (#{inFilters.status[0]}, #{inFilters.status[1]})")
                .contains("AND due_at <= #{notAfterFilters.due_at}")
                .contains("ORDER BY due_at ASC, pid ASC LIMIT #{limit} FOR UPDATE SKIP LOCKED")
                .contains("UPDATE mt_job AS target SET status = #{claimValues.status}")
                .contains("leased_until = #{claimValues.leased_until}")
                .contains("row_version = target.row_version + 1")
                .contains("WHERE target.pid = candidates.pid AND target.tenant_id = #{tenantId}")
                .endsWith("RETURNING target.*")
                .doesNotContain("pending", "retry", "2026-08-24");
    }

    @Test
    void rejectsIdentifiersThatDidNotComeFromSafeMetadata() {
        Map<String, Object> parameters = parameters();
        parameters.put("tableName", "mt_job; DROP TABLE mt_job");

        assertThatThrownBy(() -> DynamicSqlProvider.atomicBatchClaimReturning(parameters))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, Object> parameters() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("tableName", "mt_job");
        parameters.put("pkColumn", "pid");
        parameters.put("tenantId", 7L);
        parameters.put("currentUserId", 0L);
        parameters.put("limit", 2);
        parameters.put("softDelete", false);
        parameters.put("exactFilters", Map.of("lane", 3));
        parameters.put("inFilters", Map.of("status", List.of("pending", "retry")));
        parameters.put("notAfterFilters", Map.of(
                "due_at", Timestamp.from(Instant.parse("2026-08-24T00:00:00Z"))));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("status", "publishing");
        claims.put("leased_until", Timestamp.from(Instant.parse("2026-08-24T00:02:00Z")));
        parameters.put("claimValues", claims);
        parameters.put("orderByColumns", List.of("due_at"));
        return parameters;
    }
}
