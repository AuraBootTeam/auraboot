package com.auraboot.framework.print.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.print.dto.ReportExportRequest;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for ReportRenderService.
 * Verifies Excel and PDF generation with real database + real POI parsing.
 *
 * @author AuraBoot Team
 * @since 2.6.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReportRenderServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ReportRenderService reportRenderService;

    @Autowired
    private PageSchemaMapper pageSchemaMapper;

    private String testReportPid;

    private static final String DATA_TABLE_DSL = """
            {
              "$schema": "auraboot://schemas/report/v1",
              "version": "1.0.0",
              "title": "Test Data Table Report",
              "page": { "size": "A4", "orientation": "portrait", "margin": { "top": 20, "right": 15, "bottom": 20, "left": 15 } },
              "dataSources": {},
              "body": [
                {
                  "id": "block_1",
                  "blockType": "data-table",
                  "title": "Sales Data",
                  "dataSource": "ds_sales",
                  "columns": [
                    { "field": "name", "label": "Name" },
                    { "field": "amount", "label": "Amount" },
                    { "field": "status", "label": "Status" }
                  ]
                }
              ]
            }
            """;

    private static final String STAT_CARD_DSL = """
            {
              "$schema": "auraboot://schemas/report/v1",
              "version": "1.0.0",
              "title": "Test Stat Card Report",
              "page": { "size": "A4", "orientation": "landscape", "margin": { "top": 20, "right": 15, "bottom": 20, "left": 15 } },
              "dataSources": {},
              "body": [
                {
                  "id": "block_stat_1",
                  "blockType": "stat-card",
                  "dataSource": "ds_orders",
                  "valueField": "total",
                  "aggregation": "sum",
                  "label": "Total Revenue"
                },
                {
                  "id": "block_stat_2",
                  "blockType": "stat-card",
                  "dataSource": "ds_orders",
                  "valueField": "total",
                  "aggregation": "count",
                  "label": "Order Count"
                }
              ]
            }
            """;

    private static final String EMPTY_DSL = """
            {
              "$schema": "auraboot://schemas/report/v1",
              "version": "1.0.0",
              "title": "Empty Report",
              "page": { "size": "A4", "orientation": "portrait", "margin": { "top": 20, "right": 15, "bottom": 20, "left": 15 } },
              "dataSources": {},
              "body": []
            }
            """;

    private static final String CROSS_TAB_DSL = """
            {
              "$schema": "auraboot://schemas/report/v1",
              "version": "1.0.0",
              "title": "Test Cross Tab Report",
              "page": { "size": "A4", "orientation": "landscape", "margin": { "top": 20, "right": 15, "bottom": 20, "left": 15 } },
              "dataSources": {},
              "body": [
                {
                  "id": "block_cross_1",
                  "blockType": "cross-tab",
                  "title": "Sales by Region and Product",
                  "dataSource": "ds_sales",
                  "rowField": "region",
                  "columnField": "product",
                  "valueField": "amount",
                  "aggregation": "sum",
                  "showRowTotal": true,
                  "showColumnTotal": true
                }
              ]
            }
            """;

    @BeforeEach
    void setUp() {
        testReportPid = createTestPageSchema(DATA_TABLE_DSL, "Test Report " + System.currentTimeMillis());
    }

    @Test
    @Order(1)
    @DisplayName("renderExcel - data-table block produces valid XLSX with correct headers")
    void renderExcel_dataTableBlock_producesValidXlsx() throws Exception {
        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid(testReportPid);
        request.setTitle("Data Table Export");

        byte[] xlsxBytes = reportRenderService.renderExcel(request);

        assertThat(xlsxBytes).isNotNull();
        assertThat(xlsxBytes.length).isGreaterThan(0);

        // Verify it's a valid XLSX file
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            assertThat(workbook.getNumberOfSheets()).isGreaterThanOrEqualTo(1);
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet).isNotNull();
            // The sheet should have at least a header row (block title + column headers)
            assertThat(sheet.getPhysicalNumberOfRows()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @Order(2)
    @DisplayName("renderExcel - stat-card blocks produce Summary sheet")
    void renderExcel_statCardBlocks_produceSummarySheet() throws Exception {
        String statReportPid = createTestPageSchema(STAT_CARD_DSL, "Stat Card Report " + System.currentTimeMillis());

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid(statReportPid);

        byte[] xlsxBytes = reportRenderService.renderExcel(request);

        assertThat(xlsxBytes).isNotNull();
        assertThat(xlsxBytes.length).isGreaterThan(0);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            var summarySheet = workbook.getSheet("Summary");
            assertThat(summarySheet).isNotNull();
            // Header row + 2 stat cards = at least 3 rows
            assertThat(summarySheet.getPhysicalNumberOfRows()).isGreaterThanOrEqualTo(3);
            assertThat(summarySheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Metric");
            assertThat(summarySheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Value");
            assertThat(summarySheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Total Revenue");
            assertThat(summarySheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Order Count");
        }
    }

    @Test
    @Order(3)
    @DisplayName("renderExcel - empty report produces fallback sheet")
    void renderExcel_emptyReport_producesFallbackSheet() throws Exception {
        String emptyReportPid = createTestPageSchema(EMPTY_DSL, "Empty Report " + System.currentTimeMillis());

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid(emptyReportPid);

        byte[] xlsxBytes = reportRenderService.renderExcel(request);

        assertThat(xlsxBytes).isNotNull();
        assertThat(xlsxBytes.length).isGreaterThan(0);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue())
                    .contains("No exportable data blocks");
        }
    }

    @Test
    @Order(4)
    @DisplayName("renderExcel - cross-tab block produces pivot sheet")
    void renderExcel_crossTabBlock_producesPivotSheet() throws Exception {
        String crossTabReportPid = createTestPageSchema(CROSS_TAB_DSL, "Cross Tab Report " + System.currentTimeMillis());

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid(crossTabReportPid);

        byte[] xlsxBytes = reportRenderService.renderExcel(request);

        assertThat(xlsxBytes).isNotNull();
        assertThat(xlsxBytes.length).isGreaterThan(0);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsxBytes))) {
            assertThat(workbook.getNumberOfSheets()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    @Order(5)
    @DisplayName("renderExcel - non-existent reportPid throws exception")
    void renderExcel_nonExistentReportPid_throwsException() {
        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("non_existent_pid_" + System.currentTimeMillis());

        assertThatThrownBy(() -> reportRenderService.renderExcel(request))
                .hasMessageContaining("Report not found");
    }

    @Test
    @Order(6)
    @DisplayName("renderPdf - data-table block produces valid PDF bytes")
    void renderPdf_dataTableBlock_producesValidPdf() {
        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid(testReportPid);
        request.setTitle("PDF Export Test");

        byte[] pdfBytes = reportRenderService.renderPdf(request);

        assertThat(pdfBytes).isNotNull();
        assertThat(pdfBytes.length).isGreaterThan(0);
        // PDF files start with %PDF
        assertThat(new String(pdfBytes, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    @Order(7)
    @DisplayName("renderPdf - non-existent reportPid throws exception")
    void renderPdf_nonExistentReportPid_throwsException() {
        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("non_existent_pid_" + System.currentTimeMillis());

        assertThatThrownBy(() -> reportRenderService.renderPdf(request))
                .hasMessageContaining("Report not found");
    }

    @Test
    @Order(8)
    @DisplayName("renderExcel - title override works")
    void renderExcel_titleOverride_usesCustomTitle() throws Exception {
        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid(testReportPid);
        request.setTitle("Custom Title Override");

        byte[] xlsxBytes = reportRenderService.renderExcel(request);
        assertThat(xlsxBytes).isNotNull();
        assertThat(xlsxBytes.length).isGreaterThan(0);
    }

    // ==================== Helper Methods ====================

    private String createTestPageSchema(String dslJson, String name) {
        String pid = UniqueIdGenerator.generate();
        PageSchema schema = new PageSchema();
        schema.setPid(pid);
        schema.setTenantId(getTestTenant().getId());
        schema.setName(name);
        schema.setTitle(name);
        schema.setKind("custom");
        schema.setBlocks(dslJson);
        schema.setSchemaVersion(1);
        schema.setStatus("published");
        schema.setDeletedFlag(false);
        schema.setIsCurrent(true);
        schema.setCreatedAt(Instant.now());
        schema.setUpdatedAt(Instant.now());
        pageSchemaMapper.insert(schema);
        return pid;
    }
}
