package com.auraboot.framework.application.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link AdminAuditService} contract enforcement.
 *
 * <p>The integration tests in {@link AdminAuditServiceIntegrationTest} cover the
 * happy-path async write. This class pins down the fail-loud invariants that do
 * not require a real database.
 */
class AdminAuditServiceTest {

    @Test
    @DisplayName("logAdminAction throws NPE when actorUserId is null (schema is NOT NULL)")
    void logAdminAction_throwsNPE_whenActorUserIdNull() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AdminAuditService service = new AdminAuditService(jdbc);

        assertThatNullPointerException()
                .isThrownBy(() -> service.logAdminAction(
                        100L,
                        null,
                        "tenant_admin",
                        "/api/admin/users",
                        "GET",
                        200,
                        null,
                        12))
                .withMessageContaining("actorUserId required for admin audit");

        // Fail-loud: must not attempt the insert when the actor is unidentified.
        verifyNoInteractions(jdbc);
    }
}
