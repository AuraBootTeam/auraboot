package com.auraboot.framework.bi;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bi.dao.entity.ReportEntity;
import com.auraboot.framework.bi.service.ReportStorageService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TestIdGenerator;
import com.auraboot.framework.meta.entity.AuditTrail;
import com.auraboot.framework.meta.service.impl.AuditTrailService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for the first-class {@code ab_report} storage spine (Phase 4 slice 1).
 *
 * <p>Runs against the real test DB (shared {@code aura_boot}) through the
 * {@link ReportStorageService} → {@link com.auraboot.framework.bi.dao.mapper.ReportMapper}
 * → {@code ab_report} stack. Proves:
 * <ul>
 *   <li>the {@code ab_report} table exists (migration applied);</li>
 *   <li>{@code create} → {@code findByPid} round-trips ALL columns;</li>
 *   <li>the {@code dsl} JSONB round-trips a non-trivial ReportDsl JSON as a real object
 *       (object in → equal object out, NOT a PGobject / double-escaped string — the
 *       jsonb-typeHandler gotcha);</li>
 *   <li>{@code update} mutates an existing row (incl. the jsonb {@code dsl});</li>
 *   <li>{@code softDelete} sets {@code deleted_flag} and {@code findByPid} then excludes it;</li>
 *   <li>{@code listByTenant} is tenant-scoped (only the tenant's live rows);</li>
 *   <li>the {@code uk_ab_report_tenant_code} unique constraint rejects a duplicate
 *       (tenant_id, code).</li>
 * </ul>
 *
 * <p>Uses the {@code @Commit + Propagation.NEVER} harness (each service call commits in its
 * own tx) with explicit {@link AfterEach} cleanup, so the jsonb round-trip is genuinely
 * re-read from the DB and the duplicate-key violation does not poison sibling tests.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("ab_report storage spine (Phase 4 slice 1)")
class ReportStorageServiceIT extends BaseIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** A non-trivial ReportDsl blob to prove the jsonb column round-trips a real object. */
    private static final String REPORT_DSL_JSON = """
            {
              "version": 1,
              "profile": "paged-media",
              "page": { "size": "A4", "orientation": "portrait", "margins": [20, 20, 20, 20] },
              "dataSources": [
                { "id": "ds1", "type": "namedQuery", "query": "sales.byRegion" }
              ],
              "blocks": [
                { "blockType": "header", "text": "Quarterly Sales", "align": "center" },
                { "blockType": "table", "dataSource": "ds1",
                  "columns": [
                    { "field": "region", "label": "Region" },
                    { "field": "amount", "label": "Amount", "format": "currency" }
                  ] },
                { "blockType": "footer", "text": "Page ${pageNumber}" }
              ],
              "meta": { "tags": ["finance", "q3"], "nested": { "a": [1, 2, 3], "b": true } }
            }
            """;

    @Autowired
    private ReportStorageService reportStorageService;

    @Autowired
    private AuditTrailService auditTrailService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long tenantId;
    private Long otherTenantId;
    private String codeBase;

    @BeforeEach
    void setup() {
        tenantId = TestIdGenerator.uniqueTenantId();
        otherTenantId = TestIdGenerator.uniqueTenantId();
        codeBase = "rpt_" + Long.toString(System.nanoTime() & 0xfffff, 36) + "_";
        // ab_report is a tenant table, so the global TenantLineInnerInterceptor injects
        // tenant_id = MetaContext.getCurrentTenantId() into every query/insert. Drive the
        // context to our synthetic tenant so the interceptor's auto-tenant matches the rows
        // we create (and our explicit tenant scoping is exercised on top of it).
        MetaContext.setCurrentTenantId(tenantId);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_report WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        // B6: report writes commit audit rows (REQUIRES_NEW) — clean them up too.
        jdbc.update("DELETE FROM ab_audit_trail WHERE tenant_id IN (?, ?)", tenantId, otherTenantId);
        MetaContext.clear();
    }

    private ReportEntity newReport(Long tenant, String code, String title, String dslJson) {
        ReportEntity r = new ReportEntity();
        r.setTenantId(tenant);
        r.setCode(code);
        r.setTitle(title);
        r.setProfile("paged-media");
        r.setStatus("draft");
        r.setVersion(1);
        r.setCreatedBy(101L);
        r.setDsl(dslJson);
        return r;
    }

    @Test
    @DisplayName("Table exists — a trivial count query succeeds")
    void tableExists() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM ab_report", Integer.class);
        assertThat(count).isNotNull();
    }

    @Test
    @DisplayName("create → findByPid round-trips ALL columns (incl. minted pid + audit + version)")
    void createRoundTripsAllColumns() {
        String code = codeBase + "all";
        ReportEntity created = reportStorageService.create(
                newReport(tenantId, code, "Sales Report", REPORT_DSL_JSON));

        // create() must mint id + pid and stamp audit/defaults
        assertThat(created.getId()).isNotNull();
        assertThat(created.getPid()).isNotBlank();
        assertThat(created.getPid()).hasSize(26); // ULID
        assertThat(created.getDeletedFlag()).isFalse();
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();

        ReportEntity found = reportStorageService.findByPid(created.getPid());
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(created.getId());
        assertThat(found.getPid()).isEqualTo(created.getPid());
        assertThat(found.getTenantId()).isEqualTo(tenantId);
        assertThat(found.getCode()).isEqualTo(code);
        assertThat(found.getTitle()).isEqualTo("Sales Report");
        assertThat(found.getProfile()).isEqualTo("paged-media");
        assertThat(found.getStatus()).isEqualTo("draft");
        assertThat(found.getVersion()).isEqualTo(1);
        assertThat(found.getCreatedBy()).isEqualTo(101L);
        assertThat(found.getDeletedFlag()).isFalse();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(found.getDsl()).isNotBlank();
    }

    @Test
    @DisplayName("dsl JSONB round-trips a real object (object in → equal object out, not PGobject/escaped string)")
    void dslJsonbRoundTrips() throws Exception {
        ReportEntity created = reportStorageService.create(
                newReport(tenantId, codeBase + "json", "JSON Report", REPORT_DSL_JSON));

        ReportEntity found = reportStorageService.findByPid(created.getPid());
        assertThat(found).isNotNull();

        // The read-side value must be valid JSON that parses back to an *equal* object,
        // NOT a double-escaped string (e.g. "\"{\\\"version\\\":1...}\"") and NOT a
        // PGobject.toString(). Equality is structural to ignore whitespace/key-order.
        JsonNode in = OBJECT_MAPPER.readTree(REPORT_DSL_JSON);
        JsonNode out = OBJECT_MAPPER.readTree(found.getDsl());
        assertThat(out).isEqualTo(in);

        // Spot-check nested structure survived the jsonb round-trip.
        assertThat(out.at("/page/size").asText()).isEqualTo("A4");
        assertThat(out.at("/blocks/1/blockType").asText()).isEqualTo("table");
        assertThat(out.at("/meta/nested/a/2").asInt()).isEqualTo(3);
        assertThat(out.at("/meta/nested/b").asBoolean()).isTrue();

        // And the underlying column really is jsonb (not text): jsonb operators must work.
        String region = jdbc.queryForObject(
                "SELECT dsl #>> '{blocks,1,columns,0,field}' FROM ab_report WHERE pid = ?",
                String.class, created.getPid());
        assertThat(region).isEqualTo("region");
    }

    @Test
    @DisplayName("update mutates an existing row including the jsonb dsl")
    void updateMutates() throws Exception {
        ReportEntity created = reportStorageService.create(
                newReport(tenantId, codeBase + "upd", "Before", REPORT_DSL_JSON));

        String newDsl = "{\"version\":2,\"blocks\":[{\"blockType\":\"chart\",\"chartType\":\"bar\"}]}";
        ReportEntity edit = new ReportEntity();
        edit.setPid(created.getPid());
        edit.setTitle("After");
        edit.setProfile("paged-media");
        edit.setStatus("published");
        edit.setVersion(2);
        edit.setUpdatedBy(202L);
        edit.setDsl(newDsl);

        boolean ok = reportStorageService.update(edit);
        assertThat(ok).isTrue();

        ReportEntity found = reportStorageService.findByPid(created.getPid());
        assertThat(found).isNotNull();
        assertThat(found.getTitle()).isEqualTo("After");
        assertThat(found.getStatus()).isEqualTo("published");
        assertThat(found.getVersion()).isEqualTo(2);
        assertThat(found.getUpdatedBy()).isEqualTo(202L);
        // jsonb dsl was replaced and round-trips as an object
        JsonNode out = OBJECT_MAPPER.readTree(found.getDsl());
        assertThat(out.at("/version").asInt()).isEqualTo(2);
        assertThat(out.at("/blocks/0/chartType").asText()).isEqualTo("bar");
    }

    @Test
    @DisplayName("softDelete sets deleted_flag; findByPid then excludes the row")
    void softDeleteExcludesFromFind() {
        ReportEntity created = reportStorageService.create(
                newReport(tenantId, codeBase + "del", "Doomed", REPORT_DSL_JSON));
        assertThat(reportStorageService.findByPid(created.getPid())).isNotNull();

        boolean deleted = reportStorageService.softDelete(created.getPid());
        assertThat(deleted).isTrue();

        // Logical, not physical: the row still exists with deleted_flag = true (raw SQL bypasses
        // the @TableLogic interceptor, so it sees the soft-deleted row)...
        Boolean flag = jdbc.queryForObject(
                "SELECT deleted_flag FROM ab_report WHERE pid = ?", Boolean.class, created.getPid());
        assertThat(flag).isTrue();
        // ...but the service finder excludes it (standard logic-delete).
        assertThat(reportStorageService.findByPid(created.getPid())).isNull();
    }

    @Test
    @DisplayName("listByTenant is tenant-scoped and excludes soft-deleted rows")
    void listByTenantScopedAndExcludesDeleted() {
        // setup() set MetaContext to tenantId; create two rows for it.
        ReportEntity a = reportStorageService.create(
                newReport(tenantId, codeBase + "a", "A", REPORT_DSL_JSON));
        ReportEntity b = reportStorageService.create(
                newReport(tenantId, codeBase + "b", "B", REPORT_DSL_JSON));

        // A report owned by a DIFFERENT tenant must NOT appear in tenantId's list. Switch the
        // tenant context so the interceptor stamps the row with otherTenantId, then switch back.
        MetaContext.setCurrentTenantId(otherTenantId);
        reportStorageService.create(newReport(otherTenantId, codeBase + "x", "Other", REPORT_DSL_JSON));
        MetaContext.setCurrentTenantId(tenantId);

        List<ReportEntity> mine = reportStorageService.listByTenant(tenantId);
        assertThat(mine).extracting(ReportEntity::getPid)
                .containsExactlyInAnyOrder(a.getPid(), b.getPid());
        assertThat(mine).allMatch(r -> r.getTenantId().equals(tenantId));

        // soft-delete one → it drops out of the tenant list
        reportStorageService.softDelete(a.getPid());
        List<ReportEntity> afterDelete = reportStorageService.listByTenant(tenantId);
        assertThat(afterDelete).extracting(ReportEntity::getPid)
                .containsExactly(b.getPid());

        // the other tenant only ever sees its own row (query under its own context)
        MetaContext.setCurrentTenantId(otherTenantId);
        List<ReportEntity> other = reportStorageService.listByTenant(otherTenantId);
        assertThat(other).hasSize(1);
        assertThat(other.get(0).getTenantId()).isEqualTo(otherTenantId);
        MetaContext.setCurrentTenantId(tenantId);
    }

    @Test
    @DisplayName("uk_ab_report_tenant_code rejects a duplicate (tenant_id, code)")
    void uniqueTenantCodeRejectsDuplicate() {
        String dupCode = codeBase + "dup";
        reportStorageService.create(newReport(tenantId, dupCode, "First", REPORT_DSL_JSON));

        // same tenant + same code → unique violation
        assertThatThrownBy(() ->
                reportStorageService.create(newReport(tenantId, dupCode, "Second", REPORT_DSL_JSON)))
                .isInstanceOfAny(DuplicateKeyException.class, DataIntegrityViolationException.class);

        // same code under a DIFFERENT tenant is allowed (the constraint is (tenant_id, code)).
        // Switch the tenant context so the interceptor stamps the row with otherTenantId.
        MetaContext.setCurrentTenantId(otherTenantId);
        ReportEntity otherTenantSameCode =
                reportStorageService.create(newReport(otherTenantId, dupCode, "Other", REPORT_DSL_JSON));
        assertThat(otherTenantSameCode.getId()).isNotNull();
        assertThat(otherTenantSameCode.getTenantId()).isEqualTo(otherTenantId);
        MetaContext.setCurrentTenantId(tenantId);
    }

    @Test
    @DisplayName("B6 / Q15: create then update each commit a REPORT audit row (CREATE then UPDATE)")
    void reportWritesEmitAuditRows() {
        // Drive a FULL MetaContext (tenant + user) so the audit captures the actor; the audit
        // recorder sources tenant from the entity and actor from MetaContext.getCurrentUserId().
        Long actorId = TestIdGenerator.uniqueUserId();
        MetaContext.setContext(tenantId, actorId, "user-pid", "tester");

        // CREATE → exactly one audit row, operationType=CREATE
        ReportEntity created = reportStorageService.create(
                newReport(tenantId, codeBase + "audit", "Audited", REPORT_DSL_JSON));

        List<AuditTrail> afterCreate =
                auditTrailService.getAuditTrailByPid(tenantId, "report", created.getPid());
        assertThat(afterCreate).hasSize(1);
        AuditTrail createAudit = afterCreate.get(0);
        assertThat(createAudit.getEventType()).isEqualTo("REPORT");
        assertThat(createAudit.getEntityType()).isEqualTo("report");
        assertThat(createAudit.getEntityPid()).isEqualTo(created.getPid());
        assertThat(createAudit.getEntityId()).isEqualTo(created.getId());
        assertThat(createAudit.getOperationType()).isEqualTo("CREATE");
        assertThat(createAudit.getTenantId()).isEqualTo(tenantId);
        assertThat(createAudit.getActorId()).isEqualTo(actorId);

        // UPDATE → a second audit row, operationType=UPDATE (ordered after CREATE by sequence_no)
        ReportEntity edit = new ReportEntity();
        edit.setPid(created.getPid());
        edit.setTitle("Audited v2");
        edit.setProfile("paged-media");
        edit.setStatus("published");
        edit.setVersion(2);
        edit.setUpdatedBy(actorId);
        edit.setDsl("{\"version\":2}");
        assertThat(reportStorageService.update(edit)).isTrue();

        List<AuditTrail> afterUpdate =
                auditTrailService.getAuditTrailByPid(tenantId, "report", created.getPid());
        assertThat(afterUpdate)
                .extracting(AuditTrail::getOperationType)
                .containsExactly("CREATE", "UPDATE");
        assertThat(afterUpdate.get(1).getEntityPid()).isEqualTo(created.getPid());
        assertThat(afterUpdate.get(1).getActorId()).isEqualTo(actorId);
    }

    @Test
    @DisplayName("B6: upsertByPid emits CREATE on first save then UPDATE on the second")
    void upsertEmitsCreateThenUpdate() {
        Long actorId = TestIdGenerator.uniqueUserId();
        MetaContext.setContext(tenantId, actorId, "user-pid", "tester");

        String pid = "RPT" + Long.toString(System.nanoTime() & 0xffffffffL, 36).toUpperCase();
        ReportEntity first = new ReportEntity();
        first.setPid(pid);
        first.setTenantId(tenantId);
        first.setCode(codeBase + "ups");
        first.setTitle("Upsert v1");
        first.setProfile("paged-media");
        first.setDsl(REPORT_DSL_JSON);
        first.setCreatedBy(actorId);
        first.setUpdatedBy(actorId);
        // first upsert with a not-yet-existing pid → create branch (audits CREATE via create())
        reportStorageService.upsertByPid(first);

        ReportEntity second = new ReportEntity();
        second.setPid(pid);
        second.setTenantId(tenantId);
        second.setTitle("Upsert v2");
        second.setProfile("paged-media");
        second.setDsl("{\"version\":2}");
        second.setUpdatedBy(actorId);
        // second upsert with the same pid → update branch (audits UPDATE)
        reportStorageService.upsertByPid(second);

        List<AuditTrail> audits =
                auditTrailService.getAuditTrailByPid(tenantId, "report", pid);
        assertThat(audits)
                .extracting(AuditTrail::getOperationType)
                .containsExactly("CREATE", "UPDATE");
    }
}
