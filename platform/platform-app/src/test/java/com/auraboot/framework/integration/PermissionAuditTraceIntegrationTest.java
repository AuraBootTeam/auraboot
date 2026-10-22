package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.model.PermissionExplanation;
import com.auraboot.framework.permission.service.PermissionAuditService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("Permission audit trace correlation")
class PermissionAuditTraceIntegrationTest extends BaseIntegrationTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired
    private PermissionAuditService permissionAuditService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long tenantId;

    @AfterEach
    void cleanupAuditRows() {
        if (tenantId != null) {
            jdbcTemplate.update(
                    "DELETE FROM ab_permission_audit_log WHERE tenant_id = ?", tenantId);
        }
    }

    @Test
    @DisplayName("async denial writer carries the request trace snapshot")
    void asyncDenialWriterCarriesTraceSnapshot() {
        tenantId = System.currentTimeMillis();
        MetaContext.setOtelTraceId(TRACE_ID);
        PermissionExplanation denial = new PermissionExplanation(
                42L,
                "crm_account_common",
                "delete",
                null,
                "ACCOUNT-1",
                false,
                List.of(new EvaluationStep(
                        "RBAC", EvaluationVerdict.DENY, "delete denied")));

        permissionAuditService.logEvaluation(tenantId, denial);

        Awaitility.await()
                .atMost(Duration.ofSeconds(3))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    Map<String, Object> row = jdbcTemplate.queryForMap(
                            "SELECT trace_id, resource_code, action_code "
                                    + "FROM ab_permission_audit_log "
                                    + "WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 1",
                            tenantId);
                    assertThat(row.get("trace_id")).isEqualTo(TRACE_ID);
                    assertThat(row.get("resource_code")).isEqualTo("crm_account_common");
                    assertThat(row.get("action_code")).isEqualTo("delete");
                });
    }
}
