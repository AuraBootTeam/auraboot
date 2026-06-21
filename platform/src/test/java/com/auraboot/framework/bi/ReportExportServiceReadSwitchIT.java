package com.auraboot.framework.bi;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bi.dao.entity.ReportEntity;
import com.auraboot.framework.bi.dto.ReportExportFile;
import com.auraboot.framework.bi.dto.ReportExportRequest;
import com.auraboot.framework.bi.service.ReportExportService;
import com.auraboot.framework.bi.service.ReportStorageService;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.TestIdGenerator;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-stack read-switch IT for the report export service (Phase 4 slice 2b-2).
 *
 * <p>Proves {@link ReportExportService} reads the ReportDsl from the first-class {@code ab_report}
 * store FIRST (via {@link ReportStorageService} → {@code ab_report}), against a genuinely committed
 * row re-read from the DB, and that the parsed dsl renders a correct export. Also proves the
 * page-schema fallback branch is exercised when no {@code ab_report} row exists (a pid present in
 * neither store 404s through the legacy {@code pageSchemaMapper.selectByPid} path).
 *
 * <p>Uses the {@code @Commit + Propagation.NEVER} harness (mirrors {@link ReportStorageServiceIT})
 * so the {@code ab_report} row is committed and genuinely re-read, with explicit {@link AfterEach}
 * cleanup.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("report export read-switch: ab_report-first + page-schema fallback (Phase 4 slice 2b-2)")
class ReportExportServiceReadSwitchIT extends BaseIntegrationTest {

    /** Same static-table ReportDsl shape the page-schema path stores in extension.reportDsl. */
    private static final String REPORT_DSL_JSON = """
            {
              "$schema": "auraboot://schemas/report/v1",
              "version": "1.0.0",
              "title": "Operations Export",
              "dataSources": {
                "orders": {
                  "type": "static",
                  "data": [
                    { "region": "North", "cases": 12 },
                    { "region": "South", "cases": 9 }
                  ]
                }
              },
              "body": [
                {
                  "id": "table-orders",
                  "blockType": "table",
                  "title": "Orders Export",
                  "dataSource": "orders",
                  "showHeader": true,
                  "columns": [
                    { "field": "region", "label": "Region" },
                    { "field": "cases", "label": "Cases" }
                  ]
                }
              ]
            }
            """;

    @Autowired
    private ReportExportService reportExportService;

    @Autowired
    private ReportStorageService reportStorageService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = TestIdGenerator.uniqueTenantId();
        // ab_report is a tenant table; drive MetaContext so the tenant interceptor's auto-tenant
        // matches the rows we create through the storage service.
        MetaContext.setCurrentTenantId(tenantId);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_report WHERE tenant_id = ?", tenantId);
        MetaContext.clear();
    }

    @Test
    @DisplayName("ab_report-first: a committed ab_report row is read and renders the correct export")
    void exportReadsAbReportFirst() throws Exception {
        ReportEntity report = new ReportEntity();
        report.setTenantId(tenantId);
        report.setCode("rpt_readswitch_" + Long.toString(System.nanoTime() & 0xfffff, 36));
        report.setTitle("Operations Export");
        report.setProfile("paged-media");
        report.setDsl(REPORT_DSL_JSON);
        report.setCreatedBy(101L);
        report.setUpdatedBy(101L);
        ReportEntity created = reportStorageService.create(report);
        assertThat(created.getPid()).isNotBlank();

        // genuinely re-read from the DB (the row is committed)
        assertThat(reportStorageService.findByPid(created.getPid())).isNotNull();

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid(created.getPid());

        ReportExportFile excel = reportExportService.exportExcel(request);
        assertThat(excel.getFilename()).isEqualTo("Operations Export.xlsx");
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excel.getBytes()))) {
            assertThat(workbook.getSheetName(0)).isEqualTo("Orders Export");
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Region");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("North");
            assertThat(sheet.getRow(2).getCell(1).getNumericCellValue()).isEqualTo(12.0);
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("South");
            assertThat(sheet.getRow(3).getCell(1).getNumericCellValue()).isEqualTo(9.0);
        }

        // PDF export also reads ab_report (and the schedule path shares loadReportDsl)
        ReportExportFile pdf = reportExportService.exportPdf(request);
        assertThat(pdf.getBytes()).startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F', (byte) '-');
    }

    @Test
    @DisplayName("fallback: no ab_report row → delegates to page-schema (unknown pid 404s through legacy path)")
    void exportFallsBackToPageSchemaWhenNoAbReportRow() {
        // A pid that exists in NEITHER ab_report NOR ab_page_schema. The read switch finds no
        // ab_report row and falls back to pageSchemaMapper.selectByPid, which returns null → the
        // legacy "Report not found" 404. This proves the fallback branch is actually taken.
        String unknownPid = "RPT-NEITHER-STORE-" + System.nanoTime();
        assertThat(reportStorageService.findByPid(unknownPid)).isNull();

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid(unknownPid);

        assertThatThrownBy(() -> reportExportService.exportExcel(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Report not found");
    }
}
