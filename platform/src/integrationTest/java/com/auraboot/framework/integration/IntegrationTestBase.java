package com.auraboot.framework.integration;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lightweight base class for platform integration tests (Phase 2.1 IT scaffolding).
 *
 * <p>Differences vs {@link BaseIntegrationTest}:
 * <ul>
 *   <li>Does NOT auto-create test user / tenant / role — bootstrap-style ITs need a
 *       genuinely empty DB inside the transaction.</li>
 *   <li>Profile {@code integration-test} (matches existing {@code application-integration-test.yml}).</li>
 *   <li>Provides {@link #freshDb()} helper to truncate the canonical bootstrap tables
 *       inside the test transaction (so production rows are untouched at commit time
 *       — the surrounding {@code @Transactional + @Rollback(true)} discards everything).</li>
 *   <li>{@link AutoConfigureMockMvc} is on, so endpoint ITs can autowire {@code MockMvc}.</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * class MyServiceIT extends IntegrationTestBase {
 *     @Autowired private MyService svc;
 *
 *     @Test
 *     void onFreshDb_executeProducesExpectedRow() {
 *         freshDb();
 *         svc.execute(...);
 *         // assert via jdbc
 *     }
 * }
 * }</pre>
 *
 * <p><b>Hard rule:</b> Real PostgreSQL only — no H2, no mocks. Per
 * {@code testing-backend.md}. Connection inherits from {@code application-integration-test.yml}
 * (host pg by default; override via Spring properties for isolated stacks).
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@Transactional
@Rollback(true)
public abstract class IntegrationTestBase {

    /** Canonical bootstrap tables — see Phase 2.1 of bootstrap-unified.md. */
    protected static final String[] BOOTSTRAP_TABLES = {
            "ab_system_config",
            "ab_user_role",
            "ab_tenant_member",
            "ab_role",
            "ab_user",
            "ab_tenant",
            "ab_plugin"
    };

    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * Truncate canonical bootstrap tables inside the current test transaction.
     *
     * <p>Uses {@code TRUNCATE ... CASCADE} so dependent tables (e.g. {@code ab_invitation}
     * referencing {@code ab_tenant}) are also cleared. PostgreSQL TRUNCATE is fully
     * transactional — it rolls back along with the surrounding {@code @Transactional}
     * test method, so production rows are untouched at commit time.
     *
     * <p>{@code RESTART IDENTITY} resets sequences so newly-inserted rows in the
     * same test get small predictable ids.
     *
     * <p>Idempotent: missing tables are skipped with a warning (forward-compat with
     * smaller schemas / future migrations).
     */
    protected void freshDb() {
        StringBuilder sb = new StringBuilder("TRUNCATE TABLE ");
        boolean first = true;
        for (String table : BOOTSTRAP_TABLES) {
            // Pre-flight: skip tables that don't exist on this stack.
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = current_schema() AND table_name = ?",
                    Integer.class, table);
            if (exists == null || exists == 0) {
                log.warn("freshDb: table {} not present, skipping", table);
                continue;
            }
            if (!first) {
                sb.append(", ");
            }
            sb.append(table);
            first = false;
        }
        if (first) {
            // No tables existed — nothing to do.
            return;
        }
        sb.append(" RESTART IDENTITY CASCADE");
        jdbc.execute(sb.toString());
    }

    @AfterEach
    public void clearMetaContext() {
        MetaContext.clear();
    }
}
