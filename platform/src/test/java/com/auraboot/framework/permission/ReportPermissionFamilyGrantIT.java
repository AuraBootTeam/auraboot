package com.auraboot.framework.permission;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Integration test for the B6-1 clean-report-permission-family GRANT migration
 * ({@code V20260621010000__grant_clean_report_permission_family.sql}).
 *
 * <p>Runs the EXACT migration SQL (loaded verbatim from the classpath migration resource — no
 * hand-copied SQL, so the test can never drift from what production runs) against the real test DB
 * through {@link JdbcTemplate}, and proves the regression-safety contract:
 * <ul>
 *   <li>a role granted the STOPGAP {@code meta.report.generate} ALSO holds the mapped clean codes
 *       {@code report.export.execute} + {@code report.schedule.manage} after the migration;</li>
 *   <li>a role granted {@code meta.template.read} ALSO holds {@code report.definition.view};</li>
 *   <li>a role granted {@code meta.template.update} ALSO holds {@code report.definition.manage};</li>
 *   <li>the new-code {@code ab_permission} rows are created per-tenant (permissions are per-tenant);</li>
 *   <li>re-running the migration is a NO-OP — no new permission rows, no new grants, no dup-key;</li>
 *   <li>the new grants carry the same role/tenant as the old grant (access preserved exactly).</li>
 * </ul>
 *
 * <p>Permissions are PER-TENANT (ab_permission.tenant_id NOT NULL, uq_permission_code on
 * (tenant_id, code)) and grants live in ab_role_permission, so the seed creates, for the shared test
 * tenant: one role per mapping + the stopgap permission row + the stopgap grant — then asserts the
 * migration mints the clean permission row and the clean grant for that same role/tenant.
 *
 * <p>Uses the {@code @Commit + Propagation.NEVER} harness so the seeded rows are genuinely visible to
 * the raw-SQL migration, with explicit {@link AfterEach} cleanup scoped to this run's rows.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("clean report permission family grant migration (B6-1)")
class ReportPermissionFamilyGrantIT extends BaseIntegrationTest {

    private static final String MIGRATION_RESOURCE =
            "db/migration/core/V20260621010000__grant_clean_report_permission_family.sql";

    @Autowired
    private JdbcTemplate jdbc;

    private Long tenantId;
    private String runTag;

    // role pids/ids minted for this run (cleaned up in AfterEach)
    private Long roleGenerateId;   // holds meta.report.generate
    private Long roleReadId;       // holds meta.template.read
    private Long roleUpdateId;     // holds meta.template.update

    @BeforeEach
    void setup() {
        applyTestMetaContext();
        tenantId = testTenant.getId();
        runTag = Long.toString(System.nanoTime() & 0x7fffffffffffffffL, 36);
    }

    @AfterEach
    void cleanup() {
        // Drop everything this run created (scoped to this tenant + run tag). Grants first (FK-free but
        // tidy), then the seeded stopgap perms, the migration-minted clean perms, and the roles.
        for (Long roleId : new Long[]{roleGenerateId, roleReadId, roleUpdateId}) {
            if (roleId != null) {
                jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id = ? AND role_id = ?", tenantId, roleId);
            }
        }
        // seeded stopgap permission rows (scoped to this run via pid prefix)
        jdbc.update("DELETE FROM ab_permission WHERE tenant_id = ? AND pid LIKE ?", tenantId, "seed_" + runTag + "_%");
        // migration-minted clean permission rows for this tenant (only this test seeds report codes for
        // the integration-test-tenant, which starts with none, so this is safe + scoped to the tenant)
        jdbc.update(
                "DELETE FROM ab_permission WHERE tenant_id = ? AND code IN "
                        + "('report.definition.view','report.definition.manage','report.export.execute','report.schedule.manage')",
                tenantId);
        if (roleGenerateId != null) jdbc.update("DELETE FROM ab_role WHERE id = ?", roleGenerateId);
        if (roleReadId != null) jdbc.update("DELETE FROM ab_role WHERE id = ?", roleReadId);
        if (roleUpdateId != null) jdbc.update("DELETE FROM ab_role WHERE id = ?", roleUpdateId);
        MetaContext.clear();
    }

    @Test
    @DisplayName("old-code holders gain the mapped clean codes; re-running is a no-op")
    void grantMigrationContract() {
        // --- Seed: three roles, each granted ONE stopgap report code -----------------------------
        roleGenerateId = seedRole("gen");
        roleReadId = seedRole("read");
        roleUpdateId = seedRole("upd");

        Long permGenerateId = seedStopgapPermission("meta.report.generate", "gen");
        Long permReadId = seedStopgapPermission("meta.template.read", "tread");
        Long permUpdateId = seedStopgapPermission("meta.template.update", "tupd");

        seedGrant(roleGenerateId, permGenerateId);
        seedGrant(roleReadId, permReadId);
        seedGrant(roleUpdateId, permUpdateId);

        // Sanity: before the migration none of the roles hold any clean report.* code.
        assertThat(roleHoldsCode(roleGenerateId, "report.export.execute")).isFalse();
        assertThat(roleHoldsCode(roleGenerateId, "report.schedule.manage")).isFalse();
        assertThat(roleHoldsCode(roleReadId, "report.definition.view")).isFalse();
        assertThat(roleHoldsCode(roleUpdateId, "report.definition.manage")).isFalse();
        // and the clean permission rows do not exist for this tenant yet
        assertThat(cleanCodeCount()).isZero();

        // --- Run the ACTUAL migration SQL ---------------------------------------------------------
        runGrantMigration();

        // --- Assert: clean permission rows now exist per-tenant (one each) ------------------------
        assertThat(permRowCount("report.definition.view")).isEqualTo(1L);
        assertThat(permRowCount("report.definition.manage")).isEqualTo(1L);
        assertThat(permRowCount("report.export.execute")).isEqualTo(1L);
        assertThat(permRowCount("report.schedule.manage")).isEqualTo(1L);

        // --- Assert: each role ALSO holds the mapped clean code(s) --------------------------------
        // meta.report.generate -> report.export.execute AND report.schedule.manage
        assertThat(roleHoldsCode(roleGenerateId, "report.export.execute"))
                .as("meta.report.generate holder now holds report.export.execute").isTrue();
        assertThat(roleHoldsCode(roleGenerateId, "report.schedule.manage"))
                .as("meta.report.generate holder now holds report.schedule.manage").isTrue();
        // meta.template.read -> report.definition.view
        assertThat(roleHoldsCode(roleReadId, "report.definition.view"))
                .as("meta.template.read holder now holds report.definition.view").isTrue();
        // meta.template.update -> report.definition.manage
        assertThat(roleHoldsCode(roleUpdateId, "report.definition.manage"))
                .as("meta.template.update holder now holds report.definition.manage").isTrue();

        // --- Assert: NO cross-grant leakage (the read-role did not gain export, etc.) -------------
        assertThat(roleHoldsCode(roleReadId, "report.export.execute"))
                .as("a meta.template.read holder must NOT gain report.export.execute").isFalse();
        assertThat(roleHoldsCode(roleUpdateId, "report.definition.view"))
                .as("a meta.template.update holder must NOT gain report.definition.view").isFalse();

        // --- Assert: re-running is a no-op (no new perm rows, no new grants, no dup-key) ----------
        long grantsBefore = totalRunGrants();
        long permsBefore = cleanCodeCount();
        Throwable rerun = catchThrowable(this::runGrantMigration);
        assertThat(rerun).as("re-running the idempotent grant migration does not raise (no dup-key)").isNull();
        assertThat(cleanCodeCount()).as("re-run inserts no new clean permission rows").isEqualTo(permsBefore);
        assertThat(totalRunGrants()).as("re-run inserts no new grants").isEqualTo(grantsBefore);

        // After everything, the access is exactly the mapped set: generate->2, read->1, update->1.
        assertThat(roleCleanGrantCount(roleGenerateId)).isEqualTo(2L);
        assertThat(roleCleanGrantCount(roleReadId)).isEqualTo(1L);
        assertThat(roleCleanGrantCount(roleUpdateId)).isEqualTo(1L);
    }

    // ----------------------------------------------------------------------------------------------
    // Seed helpers

    private Long seedRole(String slug) {
        long id = (System.nanoTime() & 0x0000_7fff_ffff_ffffL) | 0x4000_0000_0000_0000L; // unique positive bigint
        jdbc.update(
                "INSERT INTO ab_role (id, pid, tenant_id, name, code, type, scope_type, status, "
                        + "is_default, is_system, deleted_flag, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'custom', 'tenant', 'active', false, false, false, NOW(), NOW())",
                id, UniqueIdGenerator.generate(), tenantId,
                "B6 grant IT role " + runTag + " " + slug,
                "b6_grant_it_" + runTag + "_" + slug);
        return id;
    }

    /** Insert a stopgap report permission row for the test tenant (id is identity → omitted). */
    private Long seedStopgapPermission(String code, String slug) {
        // pid carries the run tag (so AfterEach can scope the cleanup) plus a distinct slug per code
        // (so two stopgap codes can never collide on the globally-unique pid).
        String pid = "seed_" + runTag + "_" + slug;
        jdbc.update(
                "INSERT INTO ab_permission (pid, tenant_id, code, name, status, deleted_flag, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'active', false, NOW(), NOW())",
                pid, tenantId, code, "Seed " + code);
        return jdbc.queryForObject(
                "SELECT id FROM ab_permission WHERE tenant_id = ? AND code = ? AND pid = ?",
                Long.class, tenantId, code, pid);
    }

    /** Grant a permission to a role (id is identity → omitted). */
    private void seedGrant(Long roleId, Long permissionId) {
        jdbc.update(
                "INSERT INTO ab_role_permission (pid, tenant_id, role_id, permission_id, grant_type, status, "
                        + "deleted_flag, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'grant', 'active', false, NOW(), NOW())",
                UniqueIdGenerator.generate(), tenantId, roleId, permissionId);
    }

    // ----------------------------------------------------------------------------------------------
    // Assertion helpers

    /** True iff the role has an active grant whose permission has the given code (this tenant). */
    private boolean roleHoldsCode(Long roleId, String code) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM ab_role_permission rp "
                        + "JOIN ab_permission p ON p.id = rp.permission_id "
                        + "WHERE rp.tenant_id = ? AND rp.role_id = ? AND rp.deleted_flag = false "
                        + "AND p.code = ? AND p.tenant_id = ?",
                Long.class, tenantId, roleId, code, tenantId);
        return n != null && n > 0;
    }

    /** Count of clean grants (any of the 4 new codes) held by a role. */
    private long roleCleanGrantCount(Long roleId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM ab_role_permission rp "
                        + "JOIN ab_permission p ON p.id = rp.permission_id "
                        + "WHERE rp.tenant_id = ? AND rp.role_id = ? AND rp.deleted_flag = false "
                        + "AND p.code IN ('report.definition.view','report.definition.manage',"
                        + "'report.export.execute','report.schedule.manage')",
                Long.class, tenantId, roleId);
        return n == null ? 0 : n;
    }

    /** Number of ab_permission rows with this clean code for the test tenant. */
    private long permRowCount(String code) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM ab_permission WHERE tenant_id = ? AND code = ?",
                Long.class, tenantId, code);
        return n == null ? 0 : n;
    }

    /** Total clean-code permission rows for this tenant (all 4 codes). */
    private long cleanCodeCount() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM ab_permission WHERE tenant_id = ? AND code IN "
                        + "('report.definition.view','report.definition.manage',"
                        + "'report.export.execute','report.schedule.manage')",
                Long.class, tenantId);
        return n == null ? 0 : n;
    }

    /** Total clean-code grants across this run's three roles. */
    private long totalRunGrants() {
        return roleCleanGrantCount(roleGenerateId)
                + roleCleanGrantCount(roleReadId)
                + roleCleanGrantCount(roleUpdateId);
    }

    // ----------------------------------------------------------------------------------------------

    private void runGrantMigration() {
        jdbc.execute(loadMigrationSql());
    }

    private String loadMigrationSql() {
        try (InputStream is = new ClassPathResource(MIGRATION_RESOURCE).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load grant migration resource: " + MIGRATION_RESOURCE, e);
        }
    }

    @SuppressWarnings("unused")
    private static final List<String> NEW_CODES = List.of(
            "report.definition.view", "report.definition.manage",
            "report.export.execute", "report.schedule.manage");
}
