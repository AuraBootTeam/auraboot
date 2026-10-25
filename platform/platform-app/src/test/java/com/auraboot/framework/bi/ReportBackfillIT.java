package com.auraboot.framework.bi;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bi.dao.entity.ReportEntity;
import com.auraboot.framework.bi.service.ReportStorageService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.PageSchemaCreateRequest;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.service.PageSchemaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Integration test for the Phase 4 slice 3 report BACKFILL data migration
 * ({@code V20260621000200__backfill_report_definitions.sql}).
 *
 * <p>Runs the EXACT migration SQL (loaded verbatim from the classpath migration resource — no
 * hand-copied SQL, so the test can never drift from what production runs) against the real
 * Flyway test DB through {@link JdbcTemplate}, then asserts the contract:
 * <ul>
 *   <li>page-schema report shells NOT yet in {@code ab_report} are backfilled with
 *       {@code pid == page.pid}, {@code code == page.page_key}, an equal {@code dsl} object,
 *       {@code profile == 'paged-media'}, and the page's {@code tenant_id} carried;</li>
 *   <li>a report ALREADY present in {@code ab_report} (a dual-written one) with a DIFFERENT dsl
 *       is left completely untouched (its dsl/title are not overwritten);</li>
 *   <li>a non-report page (no {@code extension.reportDsl}) is ignored;</li>
 *   <li>re-running the backfill is a no-op — same row count, no duplicate-key error;</li>
 *   <li>the backfilled {@code dsl} round-trips as a real JSON object (not a PGobject / escaped
 *       string) through {@link ReportStorageService#findByPid}.</li>
 * </ul>
 *
 * <p>Uses the {@code @Commit + Propagation.NEVER} harness (each write commits in its own tx) so
 * the seeded page shells / pre-existing ab_report rows are genuinely visible to the raw-SQL
 * backfill, with explicit {@link AfterEach} cleanup scoped to this run's rows.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("ab_report backfill migration (Phase 4 slice 3)")
class ReportBackfillIT extends BaseIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String MIGRATION_RESOURCE =
            "db/migration/core/V20260621000200__backfill_report_definitions.sql";

    /** A distinct ReportDsl per shell so we can prove the right dsl landed on the right report. */
    private static String reportDslJson(String title, String region) {
        return """
                {
                  "$schema": "auraboot://schemas/report/v1",
                  "version": "1.0.0",
                  "title": "%s",
                  "dataSources": {
                    "rows": { "type": "static", "data": [ { "region": "%s", "amount": 42 } ] }
                  },
                  "body": [
                    { "id": "t1", "blockType": "table", "dataSource": "rows",
                      "columns": [ { "field": "region", "label": "Region" } ] }
                  ]
                }
                """.formatted(title, region);
    }

    @Autowired
    private PageSchemaService pageSchemaService;

    @Autowired
    private ReportStorageService reportStorageService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long tenantId;
    private String runTag;

    @BeforeEach
    void setup() {
        // Use the committed shared test identity so PageSchemaService.create can resolve a real
        // env_id (ab_page_schema.env_id is NOT NULL with an FK to ab_environment) and stamp the
        // real tenant. All assertions below are scoped to this tenant + a unique run tag, so any
        // other report shells already in the DB do not affect the test.
        applyTestMetaContext();
        tenantId = testTenant.getId();
        runTag = Long.toString(System.nanoTime() & 0x7fffffffffffffffL, 36);
    }

    @AfterEach
    void cleanup() {
        // Drop everything this run created (scoped to this tenant + run tag). ab_report rows are
        // keyed by the page pids we minted; page-schema rows by their page_key prefix.
        jdbc.update(
                "DELETE FROM ab_report WHERE tenant_id = ? AND code LIKE ?",
                tenantId, "report_bf_" + runTag + "_%");
        jdbc.update(
                "DELETE FROM ab_page_schema WHERE tenant_id = ? AND page_key LIKE ?",
                tenantId, "report_bf_" + runTag + "_%");
        MetaContext.clear();
    }

    @Test
    @DisplayName("backfills un-synced shells, leaves an already-present report untouched, ignores "
            + "non-report pages, and re-running is a no-op")
    void backfillContract() throws Exception {
        // --- Seed: two report shells NOT yet in ab_report ----------------------------------------
        String dslAJson = reportDslJson("Q3 Sales", "North");
        String dslBJson = reportDslJson("Q4 Sales", "South");
        PageSchemaDTO shellA = createReportShell("a", "Q3 Sales", dslAJson);
        PageSchemaDTO shellB = createReportShell("b", "Q4 Sales", dslBJson);

        // Sanity: the shell really stored a reportDsl in the page-schema extension (in whichever
        // form the converter chose). The backfill predicate must find it.
        Long shellReportDslCount = jdbc.queryForObject(
                "SELECT count(*) FROM ab_page_schema WHERE pid IN (?, ?) "
                        + "AND ((extension #> '{extension,reportDsl}') IS NOT NULL "
                        + "     OR (extension #> '{reportDsl}') IS NOT NULL)",
                Long.class, shellA.getPid(), shellB.getPid());
        assertThat(shellReportDslCount)
                .as("both seeded shells carry an extension.reportDsl the backfill can read")
                .isEqualTo(2L);

        // --- Seed: a report that is ALREADY in ab_report (a dual-written one) with DIFFERENT dsl --
        String dslCOriginalJson = reportDslJson("Already Synced (do not overwrite)", "West");
        PageSchemaDTO shellC = createReportShell("c", "Already Synced", reportDslJson("Stale shell", "East"));
        ReportEntity preexisting = new ReportEntity();
        preexisting.setPid(shellC.getPid());                // SAME pid as the page (dual-write contract)
        preexisting.setTenantId(tenantId);
        preexisting.setCode(shellC.getPageKey());
        preexisting.setTitle("Already Synced (do not overwrite)");
        preexisting.setProfile("paged-media");
        preexisting.setStatus("published");
        preexisting.setDsl(dslCOriginalJson);
        preexisting.setCreatedBy(101L);
        preexisting.setUpdatedBy(101L);
        ReportEntity preexistingSaved = reportStorageService.upsertByPid(preexisting);
        Long preexistingId = preexistingSaved.getId();
        assertThat(preexistingId).isNotNull();

        // --- Seed: a non-report page (kind=list, profile=admin, NO reportDsl) — must be ignored ---
        PageSchemaDTO nonReport = createNonReportPage("plain");

        // --- Run the ACTUAL migration SQL ---------------------------------------------------------
        runBackfillMigration();

        // --- Assert: shellA + shellB are now in ab_report, mapped correctly -----------------------
        ReportEntity backfilledA = reportStorageService.findByPid(shellA.getPid());
        ReportEntity backfilledB = reportStorageService.findByPid(shellB.getPid());

        assertBackfilledShell(backfilledA, shellA, dslAJson, "Q3 Sales", "North");
        assertBackfilledShell(backfilledB, shellB, dslBJson, "Q4 Sales", "South");

        // id is in the snowflake-disjoint negative space (= -page.id)
        Long pageAId = jdbc.queryForObject(
                "SELECT id FROM ab_page_schema WHERE pid = ?", Long.class, shellA.getPid());
        Long abReportAId = jdbc.queryForObject(
                "SELECT id FROM ab_report WHERE pid = ?", Long.class, shellA.getPid());
        assertThat(abReportAId).as("backfilled id is the negation of the page id (snowflake-safe)")
                .isEqualTo(-pageAId);
        assertThat(abReportAId).isNegative();

        // --- Assert: the already-present report (shellC) is UNTOUCHED ------------------------------
        ReportEntity afterC = reportStorageService.findByPid(shellC.getPid());
        assertThat(afterC).isNotNull();
        assertThat(afterC.getId())
                .as("the pre-existing ab_report row was not replaced")
                .isEqualTo(preexistingId);
        assertThat(afterC.getTitle())
                .as("the pre-existing title is not overwritten by the stale shell")
                .isEqualTo("Already Synced (do not overwrite)");
        JsonNode cDslOut = OBJECT_MAPPER.readTree(afterC.getDsl());
        JsonNode cDslExpected = OBJECT_MAPPER.readTree(dslCOriginalJson);
        assertThat(cDslOut)
                .as("the pre-existing dsl is preserved, NOT overwritten with the stale shell dsl")
                .isEqualTo(cDslExpected);
        // exactly one ab_report row for shellC's pid (no duplicate inserted)
        Long shellCRows = jdbc.queryForObject(
                "SELECT count(*) FROM ab_report WHERE pid = ?", Long.class, shellC.getPid());
        assertThat(shellCRows).isEqualTo(1L);

        // --- Assert: the non-report page is ignored ----------------------------------------------
        Long nonReportRows = jdbc.queryForObject(
                "SELECT count(*) FROM ab_report WHERE pid = ?", Long.class, nonReport.getPid());
        assertThat(nonReportRows).as("a page without extension.reportDsl is not backfilled").isZero();

        // --- Assert: re-running is a no-op (same count, no dup-key error) --------------------------
        Long countBefore = countRunReports();
        Throwable rerun = catchThrowable(this::runBackfillMigration);
        assertThat(rerun).as("re-running the idempotent backfill does not raise (no dup-key)").isNull();
        Long countAfter = countRunReports();
        assertThat(countAfter).as("re-running the backfill inserts no new rows").isEqualTo(countBefore);

        // re-run also did not corrupt the already-present row
        ReportEntity afterCRerun = reportStorageService.findByPid(shellC.getPid());
        assertThat(afterCRerun.getId()).isEqualTo(preexistingId);
        assertThat(OBJECT_MAPPER.readTree(afterCRerun.getDsl())).isEqualTo(cDslExpected);
    }

    // ----------------------------------------------------------------------------------------------

    private void assertBackfilledShell(ReportEntity backfilled, PageSchemaDTO shell,
                                       String expectedDslJson, String expectedTitle,
                                       String expectedRegion) throws Exception {
        assertThat(backfilled).as("shell %s was backfilled into ab_report", shell.getPid()).isNotNull();
        assertThat(backfilled.getPid()).as("pid == page.pid (identity contract)").isEqualTo(shell.getPid());
        assertThat(backfilled.getCode()).as("code == page.page_key").isEqualTo(shell.getPageKey());
        assertThat(backfilled.getTenantId()).as("tenant_id carried from the page").isEqualTo(tenantId);
        assertThat(backfilled.getProfile()).isEqualTo("paged-media");
        assertThat(backfilled.getVersion()).isEqualTo(1);
        assertThat(backfilled.getDeletedFlag()).isFalse();
        assertThat(backfilled.getTitle()).isEqualTo(expectedTitle);

        // dsl round-trips as a REAL object (not a PGobject / double-escaped string), equal to source
        JsonNode out = OBJECT_MAPPER.readTree(backfilled.getDsl());
        JsonNode in = OBJECT_MAPPER.readTree(expectedDslJson);
        assertThat(out).as("backfilled dsl equals the page's reportDsl exactly").isEqualTo(in);
        // jsonb operators work on the real column (proves it is jsonb, not text)
        String region = jdbc.queryForObject(
                "SELECT dsl #>> '{dataSources,rows,data,0,region}' FROM ab_report WHERE pid = ?",
                String.class, shell.getPid());
        assertThat(region).isEqualTo(expectedRegion);
    }

    /** Count this run's backfilled-or-present ab_report rows (scoped to tenant + run tag). */
    private Long countRunReports() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM ab_report WHERE tenant_id = ? AND code LIKE ?",
                Long.class, tenantId, "report_bf_" + runTag + "_%");
    }

    /** Create a report shell exactly as the report designer save does: kind=list, profile=report,
     *  extension.reportDsl = the ReportDsl. */
    private PageSchemaDTO createReportShell(String slug, String title, String reportDslJson)
            throws IOException {
        String pageKey = "report_bf_" + runTag + "_" + slug;
        PageSchemaCreateRequest req = new PageSchemaCreateRequest();
        req.setPageKey(pageKey);
        req.setName("Report Backfill " + runTag + " " + slug);
        req.setTitle(title);
        req.setKind("list");
        req.setProfile("report");
        req.setBlocks(List.of());
        Map<String, Object> extension = new HashMap<>();
        extension.put("reportDsl", OBJECT_MAPPER.readValue(reportDslJson, Map.class));
        req.setExtension(extension);
        return pageSchemaService.create(req);
    }

    /** A normal (non-report) list page: profile=admin, no reportDsl in extension. */
    private PageSchemaDTO createNonReportPage(String slug) {
        String pageKey = "report_bf_" + runTag + "_" + slug;
        PageSchemaCreateRequest req = new PageSchemaCreateRequest();
        req.setPageKey(pageKey);
        req.setName("Report Backfill " + runTag + " " + slug);
        req.setTitle("Plain List");
        req.setKind("list");
        req.setProfile("admin");
        req.setBlocks(List.of());
        return pageSchemaService.create(req);
    }

    /** Execute the EXACT backfill migration SQL loaded from the classpath resource. */
    private void runBackfillMigration() {
        String sql = loadMigrationSql();
        jdbc.execute(sql);
    }

    private String loadMigrationSql() {
        try (InputStream is = new ClassPathResource(MIGRATION_RESOURCE).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load backfill migration resource: " + MIGRATION_RESOURCE, e);
        }
    }
}
