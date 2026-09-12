package com.auraboot.framework.bi;

import com.auraboot.framework.bi.dao.entity.ReportEntity;
import com.auraboot.framework.bi.dto.ReportExportFile;
import com.auraboot.framework.bi.dto.ReportExportRequest;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bi.service.ReportStorageService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.bi.service.impl.ReportExportServiceImpl;
import com.auraboot.framework.bi.service.impl.ReportRenderClient;
import com.auraboot.framework.bi.service.impl.ReportRenderException;
import com.auraboot.framework.branding.BrandingIdentity;
import com.auraboot.framework.branding.DeploymentBrandingProvider;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.AuditTrailEvent;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.NamedQueryTestRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.meta.service.impl.AuditTrailService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportExportServiceTest {

    @TempDir
    private Path tempDirectory;

    @Mock
    private UserPermissionService userPermissionService;

    @Mock
    private DynamicDataService dynamicDataService;

    @Mock
    private NamedQueryService namedQueryService;

    @Mock
    private ReportStorageService reportStorageService;

    @Mock
    private AuditTrailService auditTrailService;

    @Mock
    private ReportRenderClient reportRenderClient;

    private ReportExportServiceImpl reportExportService;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(userPermissionService.hasPermission(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        reportExportService = new ReportExportServiceImpl(new ObjectMapper(),
                dynamicDataService, namedQueryService, reportStorageService, auditTrailService,
                reportRenderClient, BrandingIdentity::community, userPermissionService);
        // A successful export records an audit event sourced from MetaContext (set on every real
        // authenticated request, like the controller's MetaContext.getCurrentTenantId()); simulate it.
        MetaContext.setContext(7L, 99L, "user-pid", "tester");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void exportExcel_withStaticTableData_rendersWorkbookArtifact() throws Exception {
        ReportEntity page = new ReportEntity();
        page.setTenantId(MetaContext.getCurrentTenantId());
        page.setDsl(new ObjectMapper().valueToTree(reportDsl()).toString());

        when(reportStorageService.findByPid("rpt-001")).thenReturn(page);

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-001");

        ReportExportFile file = reportExportService.exportExcel(request);

        assertThat(file.getFilename()).isEqualTo("Operations Export.xlsx");
        assertThat(file.getContentType()).contains("spreadsheetml.sheet");
        assertThat(file.getBytes()).startsWith((byte) 'P', (byte) 'K');

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(file.getBytes()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            assertThat(workbook.getSheetName(0)).isEqualTo("Orders Export");
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Orders Export");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Region");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Cases");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("North");
            assertThat(sheet.getRow(2).getCell(1).getNumericCellValue()).isEqualTo(12.0);
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("South");
            assertThat(sheet.getRow(3).getCell(1).getNumericCellValue()).isEqualTo(9.0);
            assertThat(sheet.getFooter().getCenter()).isEqualTo("Generated by AuraBoot Platform");
            assertThat(((XSSFWorkbook) workbook).getProperties().getCoreProperties().getCreator())
                    .isEqualTo("AuraBoot Platform");
        }
    }

    @Test
    void exportArtifacts_useDeploymentBrandingProvider() throws Exception {
        ReportEntity page = new ReportEntity();
        page.setTenantId(MetaContext.getCurrentTenantId());
        page.setDsl(new ObjectMapper().valueToTree(reportDsl()).toString());
        when(reportStorageService.findByPid("rpt-branded")).thenReturn(page);

        Path brandingConfig = tempDirectory.resolve("branding.json");
        Files.writeString(brandingConfig, """
                {
                  "schemaVersion": 1,
                  "orderReference": "SO-2026-001",
                  "productName": "Northstar",
                  "platformName": "Northstar Operations Platform",
                  "logoUrl": "/customer-brand/logo.png",
                  "faviconUrl": "/customer-brand/favicon.ico",
                  "favicon32Url": "/customer-brand/favicon-32x32.png",
                  "appleTouchIconUrl": "/customer-brand/apple-touch-icon.png",
                  "manifestUrl": "/customer-brand/manifest.webmanifest",
                  "websiteUrl": "https://northstar.example.com",
                  "docsUrl": "https://northstar.example.com/docs",
                  "supportUrl": "https://northstar.example.com/support",
                  "copyrightHolder": "Northstar Holdings Ltd.",
                  "poweredByText": "Powered by Northstar",
                  "generatedByText": "Generated by Northstar Operations Platform"
                }
                """);
        DeploymentBrandingProvider customerBranding = new DeploymentBrandingProvider(
                new MockEnvironment()
                        .withProperty("EDITION", "standard")
                        .withProperty("AURABOOT_BRANDING_CONFIG_PATH", brandingConfig.toString())
                        .withProperty("AURABOOT_WHITE_LABEL_ORDER_REFERENCE", "SO-2026-001"),
                new ObjectMapper());
        ReportExportServiceImpl brandedService = new ReportExportServiceImpl(
                new ObjectMapper(),
                dynamicDataService,
                namedQueryService,
                reportStorageService,
                auditTrailService,
                reportRenderClient,
                customerBranding, userPermissionService);
        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-branded");

        ReportExportFile excel = brandedService.exportExcel(request);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excel.getBytes()))) {
            assertThat(workbook.getSheetAt(0).getFooter().getCenter())
                    .isEqualTo("Generated by Northstar Operations Platform");
            XSSFWorkbook xssfWorkbook = (XSSFWorkbook) workbook;
            assertThat(xssfWorkbook.getProperties().getCoreProperties().getCreator())
                    .isEqualTo("Northstar Operations Platform");
        }

        ReportExportFile pdf = brandedService.exportPdf(request);
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdf.getBytes()))) {
            assertThat(new PDFTextStripper().getText(document))
                    .contains("Generated by Northstar Operations Platform")
                    .doesNotContain("Generated by AuraBoot Platform");
        }
    }

    @Test
    void exportRejectsMissingOrOtherTenantReport() {
        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("unavailable");
        assertThatThrownBy(() -> reportExportService.exportJson(request))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Report not found");
        ReportEntity other = new ReportEntity();
        other.setTenantId(999L);
        other.setDsl("{}");
        when(reportStorageService.findByPid("unavailable")).thenReturn(other);
        assertThatThrownBy(() -> reportExportService.exportJson(request))
                .isInstanceOf(ValidationException.class).hasMessageContaining("Report not found");
        verify(auditTrailService, never()).recordAudit(any());
    }

    @Test
    void exportPdf_withStaticTableData_rendersPdfArtifact() throws Exception {
        ReportEntity page = new ReportEntity();
        page.setTenantId(MetaContext.getCurrentTenantId());
        page.setDsl(new ObjectMapper().valueToTree(reportDsl()).toString());

        when(reportStorageService.findByPid("rpt-pdf")).thenReturn(page);

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-pdf");

        ReportExportFile file = reportExportService.exportPdf(request);

        assertThat(file.getFilename()).isEqualTo("Operations Export.pdf");
        assertThat(file.getContentType()).isEqualTo("application/pdf");
        assertThat(file.getBytes()).startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F', (byte) '-');

        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(file.getBytes()))) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Operations Export");
            assertThat(text).contains("Orders Export");
            assertThat(text).contains("Region | Cases");
            assertThat(text).contains("North | 12");
            assertThat(text).contains("South | 9");
            assertThat(text).contains("Generated by AuraBoot Platform");
        }
    }

    // ---------- Phase 3: WYSIWYG renderer with PDFBox fallback ----------

    @Test
    void exportPdf_usesWysiwygRenderer_whenItReturnsPdfBytes() throws Exception {
        byte[] wysiwyg = createPdf("WYSIWYG renderer output");
        when(reportRenderClient.renderPdf(any(), any())).thenReturn(wysiwyg);
        stubReportDsl("rpt-wysiwyg");

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-wysiwyg");
        ReportExportFile file = reportExportService.exportPdf(request);

        assertThat(file.getContentType()).isEqualTo("application/pdf");
        assertThat(file.getFilename()).isEqualTo("Operations Export.pdf");
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(file.getBytes()))) {
            assertThat(new PDFTextStripper().getText(document))
                    .contains("WYSIWYG renderer output")
                    .contains("Generated by AuraBoot Platform");
        }
    }

    @Test
    void exportPdf_fallsBackToPdfBox_whenRendererFails() throws Exception {
        // Renderer unavailable/failed -> fall back to the legacy PDFBox text export
        // (logged, never silent), so PDF export never hard-fails on a renderer issue.
        when(reportRenderClient.renderPdf(any(), any()))
                .thenThrow(new ReportRenderException("renderer unavailable"));
        stubReportDsl("rpt-fallback");

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-fallback");
        ReportExportFile file = reportExportService.exportPdf(request);

        assertThat(file.getBytes()).startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F', (byte) '-');
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(file.getBytes()))) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Operations Export");
            assertThat(text).contains("Region | Cases");
            assertThat(text).contains("Generated by AuraBoot Platform");
        }
    }

    @Test
    void exportPdf_withPageSettings_preservesMediaBoxMarginsAndTextHierarchy() throws Exception {
        ReportEntity page = new ReportEntity();
        page.setTenantId(MetaContext.getCurrentTenantId());
        page.setDsl(new ObjectMapper().valueToTree(visualFidelityReportDsl()).toString());

        when(reportStorageService.findByPid("rpt-visual-pdf")).thenReturn(page);

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-visual-pdf");

        ReportExportFile file = reportExportService.exportPdf(request);

        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(file.getBytes()))) {
            PDRectangle mediaBox = document.getPage(0).getMediaBox();
            assertThat(mediaBox.getWidth()).isBetween(841f, 842f);
            assertThat(mediaBox.getHeight()).isBetween(595f, 596f);

            List<PdfTextLine> lines = extractPdfTextLines(document);
            PdfTextLine title = requirePdfTextLine(lines, "Visual Fidelity Export");
            PdfTextLine blockTitle = requirePdfTextLine(lines, "Layout Table");
            float expectedLeft = mmToPoints(35);

            assertThat(title.x()).isBetween(expectedLeft - 1f, expectedLeft + 1f);
            assertThat(title.fontSize()).isBetween(15.5f, 16.5f);
            assertThat(blockTitle.x()).isBetween(expectedLeft - 1f, expectedLeft + 1f);
            assertThat(blockTitle.fontSize()).isBetween(11.5f, 12.5f);
            assertThat(title.y()).isLessThan(blockTitle.y());
        }
    }

    @Test
    void exportJson_withReportDslAndResolvedRows_rendersRoundTripArtifact() throws Exception {
        ReportEntity page = new ReportEntity();
        page.setTenantId(MetaContext.getCurrentTenantId());
        page.setDsl(new ObjectMapper().valueToTree(reportDsl()).toString());

        when(reportStorageService.findByPid("rpt-json")).thenReturn(page);

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-json");

        ReportExportFile file = reportExportService.exportJson(request);

        assertThat(file.getFilename()).isEqualTo("Operations Export.report.json");
        assertThat(file.getContentType()).isEqualTo("application/json");

        Map<String, Object> payload = new ObjectMapper().readValue(
                file.getBytes(),
                new TypeReference<>() {}
        );
        assertThat(payload.get("format")).isEqualTo("auraboot.report.export.v1");
        assertThat(payload.get("reportPid")).isEqualTo("rpt-json");

        Map<String, Object> exportedDsl = castMap(payload.get("reportDsl"));
        assertThat(exportedDsl.get("title")).isEqualTo("Operations Export");
        List<Map<String, Object>> body = castList(exportedDsl.get("body"));
        assertThat(body.get(0).get("blockType")).isEqualTo("table");
        assertThat(body.get(0).get("title")).isEqualTo("Orders Export");

        Map<String, Object> dataSets = castMap(payload.get("dataSets"));
        List<Map<String, Object>> rows = castList(dataSets.get("orders"));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("region")).isEqualTo("North");
        assertThat(rows.get(0).get("cases")).isEqualTo(12);
    }

    @Test
    void exportExcel_withStaticNonTableBlocks_rendersSemanticWorkbookSheets() throws Exception {
        ReportEntity page = new ReportEntity();
        page.setTenantId(MetaContext.getCurrentTenantId());
        page.setDsl(new ObjectMapper().valueToTree(nonTableReportDsl()).toString());

        when(reportStorageService.findByPid("rpt-non-table-xlsx")).thenReturn(page);

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-non-table-xlsx");

        ReportExportFile file = reportExportService.exportExcel(request);

        assertThat(file.getFilename()).isEqualTo("Operations Non Table Export.xlsx");
        assertThat(file.getContentType()).contains("spreadsheetml.sheet");
        assertThat(file.getBytes()).startsWith((byte) 'P', (byte) 'K');

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(file.getBytes()))) {
            assertThat(workbook.getSheetName(0)).isEqualTo("Grouped Cases");
            var groupedSheet = workbook.getSheet("Grouped Cases");
            assertThat(groupedSheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Region");
            assertThat(groupedSheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("owner: Ops-A (2)");
            assertThat(groupedSheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("North");
            assertThat(groupedSheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("owner: Ops-B (1)");

            var crossTabSheet = workbook.getSheet("Region Status Matrix");
            assertThat(crossTabSheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("region \\ status");
            assertThat(crossTabSheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Closed");
            assertThat(crossTabSheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("Open");
            assertThat(crossTabSheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("North");
            assertThat(crossTabSheet.getRow(2).getCell(1).getNumericCellValue()).isEqualTo(3.0);
            assertThat(crossTabSheet.getRow(2).getCell(2).getNumericCellValue()).isEqualTo(12.0);
            assertThat(crossTabSheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("Total");
            assertThat(crossTabSheet.getRow(4).getCell(3).getNumericCellValue()).isEqualTo(24.0);

            var statSheet = workbook.getSheet("Open Case Total");
            assertThat(statSheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Total Cases");
            assertThat(statSheet.getRow(2).getCell(1).getNumericCellValue()).isEqualTo(24.0);

            var richTextSheet = workbook.getSheet("Executive Summary");
            assertThat(richTextSheet.getRow(1).getCell(0).getStringCellValue())
                    .isEqualTo("Operations summary line one");
            assertThat(richTextSheet.getRow(2).getCell(0).getStringCellValue())
                    .isEqualTo("Operations summary line two");

            var chartSheet = workbook.getSheet("Status Chart");
            assertThat(chartSheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Category");
            assertThat(chartSheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Closed");
            assertThat(chartSheet.getRow(2).getCell(1).getNumericCellValue()).isEqualTo(3.0);
            assertThat(chartSheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("Open");
            assertThat(chartSheet.getRow(3).getCell(1).getNumericCellValue()).isEqualTo(21.0);

            var textSheet = workbook.getSheet("Report Text");
            assertThat(textSheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Page Header");
            assertThat(textSheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Operations Header");
            assertThat(textSheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Barcode");
            assertThat(textSheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("OPS-2026-EXPORT");
            assertThat(textSheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("Watermark");
            assertThat(textSheet.getRow(3).getCell(1).getStringCellValue()).isEqualTo("CONFIDENTIAL");
            assertThat(textSheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("Page Footer");
            assertThat(textSheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("Operations Footer");
        }
    }

    @Test
    void exportExcel_chartBlock_embedsNativeChart() throws Exception {
        ReportEntity page = new ReportEntity();
        page.setTenantId(MetaContext.getCurrentTenantId());
        page.setDsl(new ObjectMapper().valueToTree(nonTableReportDsl()).toString());
        when(reportStorageService.findByPid("rpt-chart-native")).thenReturn(page);

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-chart-native");

        ReportExportFile file = reportExportService.exportExcel(request);

        try (XSSFWorkbook workbook =
                (XSSFWorkbook) WorkbookFactory.create(new ByteArrayInputStream(file.getBytes()))) {
            XSSFSheet chartSheet = workbook.getSheet("Status Chart");
            assertThat(chartSheet).isNotNull();
            // the Category/Value data (the chart's source) is still present
            assertThat(chartSheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Category");
            // a REAL native Excel chart is embedded over that data — not just a data sheet
            XSSFDrawing drawing = chartSheet.getDrawingPatriarch();
            assertThat(drawing).as("chart sheet must carry a drawing").isNotNull();
            assertThat(drawing.getCharts())
                    .as("a native XSSF chart must be embedded over the data")
                    .hasSize(1);
        }
    }

    @Test
    void exportExcel_withModelNamedQueryAndApiDataSources_rendersResolvedRows() throws Exception {
        ReportEntity page = new ReportEntity();
        page.setTenantId(MetaContext.getCurrentTenantId());
        page.setDsl(new ObjectMapper().valueToTree(nonStaticDataSourceReportDsl()).toString());

        when(reportStorageService.findByPid("rpt-non-static")).thenReturn(page);
        when(dynamicDataService.list(eq("rpt_case_model"), any(DynamicQueryRequest.class)))
                .thenReturn(PaginationResult.of(
                        List.of(Map.of("source", "Model", "cases", 31)),
                        1L,
                        1,
                        20));
        when(namedQueryService.executeQuery(eq("rpt_named_cases"), any(NamedQueryTestRequest.class)))
                .thenReturn(PaginationResult.of(
                        List.of(Map.of("source", "NamedQuery", "cases", 27)),
                        1L,
                        1,
                        20));
        when(namedQueryService.executeQuery(eq("rpt_api_cases"), any(NamedQueryTestRequest.class)))
                .thenReturn(PaginationResult.of(
                        List.of(Map.of("source", "API", "cases", 19)),
                        1L,
                        1,
                        20));

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-non-static");

        ReportExportFile file = reportExportService.exportExcel(request);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(file.getBytes()))) {
            assertThat(workbook.getSheet("Model Cases").getRow(2).getCell(0).getStringCellValue())
                    .isEqualTo("Model");
            assertThat(workbook.getSheet("Model Cases").getRow(2).getCell(1).getNumericCellValue())
                    .isEqualTo(31.0);
            assertThat(workbook.getSheet("NamedQuery Cases").getRow(2).getCell(0).getStringCellValue())
                    .isEqualTo("NamedQuery");
            assertThat(workbook.getSheet("NamedQuery Cases").getRow(2).getCell(1).getNumericCellValue())
                    .isEqualTo(27.0);
            assertThat(workbook.getSheet("API Cases").getRow(2).getCell(0).getStringCellValue())
                    .isEqualTo("API");
            assertThat(workbook.getSheet("API Cases").getRow(2).getCell(1).getNumericCellValue())
                    .isEqualTo(19.0);
        }

        ArgumentCaptor<DynamicQueryRequest> modelRequestCaptor =
                ArgumentCaptor.forClass(DynamicQueryRequest.class);
        verify(dynamicDataService).list(eq("rpt_case_model"), modelRequestCaptor.capture());
        DynamicQueryRequest modelRequest = modelRequestCaptor.getValue();
        assertThat(modelRequest.getConditions()).hasSize(1);
        assertThat(modelRequest.getSortFields()).hasSize(2);
        assertThat(modelRequest.getSortFields().get(0).getFieldName()).isEqualTo("e2et_order_type");
        assertThat(modelRequest.getSortFields().get(0).getDirection().name()).isEqualTo("DESC");
        assertThat(modelRequest.getSortFields().get(1).getFieldName()).isEqualTo("e2et_order_title");
        assertThat(modelRequest.getSortFields().get(1).getDirection().name()).isEqualTo("ASC");
        assertThat(modelRequest.getSortFields().get(1).getPriority()).isEqualTo(1);
        assertThat(modelRequest.getConditions().get(0).getFieldName()).isEqualTo("e2et_order_title");
        assertThat(modelRequest.getConditions().get(0).getOperator()).isEqualTo(QueryCondition.Operator.EQ);
        assertThat(modelRequest.getConditions().get(0).getValue()).isEqualTo("Model");
        verify(namedQueryService).executeQuery(eq("rpt_named_cases"), any(NamedQueryTestRequest.class));
        verify(namedQueryService).executeQuery(eq("rpt_api_cases"), any(NamedQueryTestRequest.class));
    }

    @Test
    void exportPdf_withStaticNonTableBlocks_rendersSemanticTextArtifact() throws Exception {
        ReportEntity page = new ReportEntity();
        page.setTenantId(MetaContext.getCurrentTenantId());
        page.setDsl(new ObjectMapper().valueToTree(nonTableReportDsl()).toString());

        when(reportStorageService.findByPid("rpt-non-table-pdf")).thenReturn(page);

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-non-table-pdf");

        ReportExportFile file = reportExportService.exportPdf(request);

        assertThat(file.getFilename()).isEqualTo("Operations Non Table Export.pdf");
        assertThat(file.getContentType()).isEqualTo("application/pdf");
        assertThat(file.getBytes()).startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F', (byte) '-');

        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(file.getBytes()))) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("Operations Non Table Export");
            assertThat(text).contains("Operations Header");
            assertThat(text).contains("owner: Ops-A (2)");
            assertThat(text).contains("Region Status Matrix");
            assertThat(text).contains("region \\ status | Closed | Open | Total");
            assertThat(text).contains("North | 3 | 12 | 15");
            assertThat(text).contains("Total Cases: 24");
            assertThat(text).contains("Operations summary line one");
            assertThat(text).contains("Status Chart");
            assertThat(text).contains("Closed | 3");
            assertThat(text).contains("Barcode: OPS-2026-EXPORT");
            assertThat(text).contains("Watermark: CONFIDENTIAL");
            assertThat(text).contains("Operations Footer");
        }
    }

    @Test
    void exportExcel_withoutReportDsl_throwsValidationException() {
        ReportEntity page = new ReportEntity();
        page.setTenantId(MetaContext.getCurrentTenantId());
        when(reportStorageService.findByPid("rpt-missing")).thenReturn(page);

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-missing");

        assertThatThrownBy(() -> reportExportService.exportExcel(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Report DSL not found");
    }

    // ---------- B6 / Q15: a SUCCESSFUL export records a REPORT_EXPORT audit event ----------

    @Test
    void exportExcel_recordsExportAudit() throws Exception {
        stubReportDsl("rpt-audit-xlsx");

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-audit-xlsx");
        reportExportService.exportExcel(request);

        AuditTrailEvent e = capturedExportAudit();
        assertThat(e.getEventType()).isEqualTo("REPORT_EXPORT");
        assertThat(e.getEntityType()).isEqualTo("report");
        assertThat(e.getEntityPid()).isEqualTo("rpt-audit-xlsx");
        assertThat(e.getOperationType()).isEqualTo("EXPORT_EXCEL");
        assertThat(e.getTenantId()).isEqualTo(7L);
        assertThat(e.getActorId()).isEqualTo(99L);
        assertThat(e.getMetadata().get("format").asText()).isEqualTo("excel");
        assertThat(e.getMetadata().get("filename").asText()).isEqualTo("Operations Export.xlsx");
    }

    @Test
    void exportPdf_recordsExportAudit() {
        stubReportDsl("rpt-audit-pdf");

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-audit-pdf");
        reportExportService.exportPdf(request);

        AuditTrailEvent e = capturedExportAudit();
        assertThat(e.getEventType()).isEqualTo("REPORT_EXPORT");
        assertThat(e.getOperationType()).isEqualTo("EXPORT_PDF");
        assertThat(e.getEntityPid()).isEqualTo("rpt-audit-pdf");
        assertThat(e.getMetadata().get("format").asText()).isEqualTo("pdf");
        assertThat(e.getMetadata().get("filename").asText()).isEqualTo("Operations Export.pdf");
    }

    @Test
    void exportJson_recordsExportAudit() {
        stubReportDsl("rpt-audit-json");

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-audit-json");
        reportExportService.exportJson(request);

        AuditTrailEvent e = capturedExportAudit();
        assertThat(e.getEventType()).isEqualTo("REPORT_EXPORT");
        assertThat(e.getOperationType()).isEqualTo("EXPORT_JSON");
        assertThat(e.getEntityPid()).isEqualTo("rpt-audit-json");
        assertThat(e.getMetadata().get("format").asText()).isEqualTo("json");
        assertThat(e.getMetadata().get("filename").asText()).isEqualTo("Operations Export.report.json");
    }

    @Test
    void export_withoutReportDsl_recordsNoAudit() {
        // A failed export (missing dsl) must NOT emit an audit event — audit only fires on success.
        ReportEntity page = new ReportEntity();
        page.setTenantId(MetaContext.getCurrentTenantId());
        when(reportStorageService.findByPid("rpt-no-audit")).thenReturn(page);

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-no-audit");

        assertThatThrownBy(() -> reportExportService.exportPdf(request))
                .isInstanceOf(ValidationException.class);
        verify(auditTrailService, never()).recordAudit(any());
    }

    private void stubReportDsl(String reportPid) {
        ReportEntity page = new ReportEntity();
        page.setTenantId(MetaContext.getCurrentTenantId());
        page.setDsl(new ObjectMapper().valueToTree(reportDsl()).toString());
        when(reportStorageService.findByPid(reportPid)).thenReturn(page);
    }

    private byte[] createPdf(String text) throws IOException {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12f);
                content.newLineAtOffset(48f, 760f);
                content.showText(text);
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private AuditTrailEvent capturedExportAudit() {
        ArgumentCaptor<AuditTrailEvent> c = ArgumentCaptor.forClass(AuditTrailEvent.class);
        verify(auditTrailService).recordAudit(c.capture());
        return c.getValue();
    }

    private Map<String, Object> reportDsl() {
        Map<String, Object> rowNorth = new LinkedHashMap<>();
        rowNorth.put("region", "North");
        rowNorth.put("cases", 12);
        Map<String, Object> rowSouth = new LinkedHashMap<>();
        rowSouth.put("region", "South");
        rowSouth.put("cases", 9);

        Map<String, Object> dataSource = new LinkedHashMap<>();
        dataSource.put("type", "static");
        dataSource.put("data", List.of(rowNorth, rowSouth));

        Map<String, Object> table = new LinkedHashMap<>();
        table.put("id", "table-orders");
        table.put("blockType", "table");
        table.put("title", "Orders Export");
        table.put("dataSource", "orders");
        table.put("showHeader", true);
        table.put("columns", List.of(
                Map.of("field", "region", "label", "Region"),
                Map.of("field", "cases", "label", "Cases")
        ));

        Map<String, Object> dsl = new LinkedHashMap<>();
        dsl.put("$schema", "auraboot://schemas/report/v1");
        dsl.put("version", "1.0.0");
        dsl.put("title", "Operations Export");
        dsl.put("dataSources", Map.of("orders", dataSource));
        dsl.put("body", List.of(table));
        return dsl;
    }

    private Map<String, Object> visualFidelityReportDsl() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("region", "North");
        row.put("cases", 12);

        Map<String, Object> dataSource = new LinkedHashMap<>();
        dataSource.put("type", "static");
        dataSource.put("data", List.of(row));

        Map<String, Object> table = new LinkedHashMap<>();
        table.put("id", "layout_table");
        table.put("blockType", "table");
        table.put("title", "Layout Table");
        table.put("dataSource", "layoutRows");
        table.put("showHeader", true);
        table.put("columns", List.of(
                Map.of("field", "region", "label", "Region"),
                Map.of("field", "cases", "label", "Cases")
        ));

        Map<String, Object> dsl = new LinkedHashMap<>();
        dsl.put("$schema", "auraboot://schemas/report/v1");
        dsl.put("version", "1.0.0");
        dsl.put("title", "Visual Fidelity Export");
        dsl.put("page", Map.of(
                "size", "A4",
                "orientation", "landscape",
                "margin", Map.of("top", 15, "right", 10, "bottom", 12, "left", 35)
        ));
        dsl.put("dataSources", Map.of("layoutRows", dataSource));
        dsl.put("body", List.of(table));
        return dsl;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private List<PdfTextLine> extractPdfTextLines(PDDocument document) throws IOException {
        List<PdfTextLine> lines = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
                if (text != null && !text.isBlank() && !textPositions.isEmpty()) {
                    TextPosition first = textPositions.get(0);
                    lines.add(new PdfTextLine(
                            text.trim(),
                            first.getXDirAdj(),
                            first.getYDirAdj(),
                            first.getFontSizeInPt()
                    ));
                }
                super.writeString(text, textPositions);
            }
        };
        stripper.getText(document);
        return lines;
    }

    private PdfTextLine requirePdfTextLine(List<PdfTextLine> lines, String text) {
        return lines.stream()
                .filter(line -> line.text().equals(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PDF text line not found: " + text + " in " + lines));
    }

    private float mmToPoints(float millimeters) {
        return millimeters * 72f / 25.4f;
    }

    private record PdfTextLine(String text, float x, float y, float fontSize) {
    }

    private Map<String, Object> nonTableReportDsl() {
        Map<String, Object> rowNorthOpen = new LinkedHashMap<>();
        rowNorthOpen.put("region", "North");
        rowNorthOpen.put("status", "Open");
        rowNorthOpen.put("owner", "Ops-A");
        rowNorthOpen.put("cases", 12);
        Map<String, Object> rowNorthClosed = new LinkedHashMap<>();
        rowNorthClosed.put("region", "North");
        rowNorthClosed.put("status", "Closed");
        rowNorthClosed.put("owner", "Ops-A");
        rowNorthClosed.put("cases", 3);
        Map<String, Object> rowSouthOpen = new LinkedHashMap<>();
        rowSouthOpen.put("region", "South");
        rowSouthOpen.put("status", "Open");
        rowSouthOpen.put("owner", "Ops-B");
        rowSouthOpen.put("cases", 9);

        Map<String, Object> dataSource = new LinkedHashMap<>();
        dataSource.put("type", "static");
        dataSource.put("data", List.of(rowNorthOpen, rowNorthClosed, rowSouthOpen));

        Map<String, Object> groupedTable = new LinkedHashMap<>();
        groupedTable.put("id", "grouped-cases");
        groupedTable.put("blockType", "grouped-table");
        groupedTable.put("title", "Grouped Cases");
        groupedTable.put("dataSource", "ops");
        groupedTable.put("groupByField", "owner");
        groupedTable.put("showHeader", true);
        groupedTable.put("columns", List.of(
                Map.of("field", "region", "label", "Region"),
                Map.of("field", "status", "label", "Status"),
                Map.of("field", "cases", "label", "Cases")
        ));

        Map<String, Object> crossTab = new LinkedHashMap<>();
        crossTab.put("id", "matrix");
        crossTab.put("blockType", "cross-tab");
        crossTab.put("title", "Region Status Matrix");
        crossTab.put("dataSource", "ops");
        crossTab.put("rowField", "region");
        crossTab.put("columnField", "status");
        crossTab.put("valueField", "cases");
        crossTab.put("aggregation", "sum");
        crossTab.put("showRowTotal", true);
        crossTab.put("showColumnTotal", true);

        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("id", "open-total");
        stat.put("blockType", "stat-card");
        stat.put("title", "Open Case Total");
        stat.put("dataSource", "ops");
        stat.put("valueField", "cases");
        stat.put("aggregation", "sum");
        stat.put("label", "Total Cases");

        Map<String, Object> richText = new LinkedHashMap<>();
        richText.put("id", "summary");
        richText.put("blockType", "rich-text");
        richText.put("title", "Executive Summary");
        richText.put("content", "Operations summary line one\nOperations summary line two");

        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("id", "status-chart");
        chart.put("blockType", "chart");
        chart.put("title", "Status Chart");
        chart.put("dataSource", "ops");
        chart.put("chartType", "bar");
        chart.put("categoryField", "status");
        chart.put("valueField", "cases");
        chart.put("aggregation", "sum");

        Map<String, Object> barcode = new LinkedHashMap<>();
        barcode.put("id", "barcode");
        barcode.put("blockType", "barcode");
        barcode.put("title", "Export Barcode");
        barcode.put("format", "code128");
        barcode.put("staticValue", "OPS-2026-EXPORT");

        Map<String, Object> watermark = new LinkedHashMap<>();
        watermark.put("id", "watermark");
        watermark.put("blockType", "watermark");
        watermark.put("text", "CONFIDENTIAL");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("id", "header");
        header.put("blockType", "page-header");
        header.put("content", "Operations Header");

        Map<String, Object> footer = new LinkedHashMap<>();
        footer.put("id", "footer");
        footer.put("blockType", "page-footer");
        footer.put("content", "Operations Footer");

        Map<String, Object> dsl = new LinkedHashMap<>();
        dsl.put("$schema", "auraboot://schemas/report/v1");
        dsl.put("version", "1.0.0");
        dsl.put("title", "Operations Non Table Export");
        dsl.put("dataSources", Map.of("ops", dataSource));
        dsl.put("body", List.of(header, groupedTable, crossTab, stat, richText, chart, barcode, watermark, footer));
        return dsl;
    }

    @Test
    void exportJson_includesRowsBeyondTheFormerDefaultLimit() throws Exception {
        ReportEntity report = new ReportEntity();
        report.setTenantId(MetaContext.getCurrentTenantId());
        report.setDsl(new ObjectMapper().writeValueAsString(Map.of("title", "All rows", "dataSources",
                Map.of("rows", Map.of("type", "model", "modelCode", "orders")), "body", List.of())));
        when(reportStorageService.findByPid("full-rows")).thenReturn(report);
        List<Map<String, Object>> rows = java.util.stream.IntStream.range(0, 201)
                .mapToObj(index -> Map.<String, Object>of("title", "row-" + index)).toList();
        when(dynamicDataService.list(eq("orders"), any())).thenReturn(PaginationResult.of(rows, 201L, 1, 1000));
        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("full-rows");
        var payload = new ObjectMapper().readTree(reportExportService.exportJson(request).getBytes());
        assertThat(payload.path("dataSets").path("rows").size()).isEqualTo(201);
        assertThat(payload.path("dataSets").path("rows").get(200).path("title").asText()).isEqualTo("row-200");
        ArgumentCaptor<DynamicQueryRequest> captured = ArgumentCaptor.forClass(DynamicQueryRequest.class);
        verify(dynamicDataService).list(eq("orders"), captured.capture());
        assertThat(captured.getValue().getPageSize()).isEqualTo(1000);
    }

    @Test
    void exportsRejectTruncatedResultsInsteadOfProducingPartialFiles() throws Exception {
        ReportEntity report = new ReportEntity();
        report.setTenantId(MetaContext.getCurrentTenantId());
        report.setDsl(new ObjectMapper().writeValueAsString(Map.of("title", "Limited", "dataSources",
                Map.of("rows", Map.of("type", "model", "modelCode", "orders", "maxItems", 2)), "body", List.of())));
        when(reportStorageService.findByPid("limited")).thenReturn(report);
        when(dynamicDataService.list(eq("orders"), any())).thenReturn(PaginationResult.of(
                List.of(Map.of("title", "first"), Map.of("title", "second")), 3L, 1, 2));
        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("limited");
        assertThatThrownBy(() -> reportExportService.exportJson(request)).isInstanceOf(ValidationException.class).hasMessageContaining("exceeds");
        assertThatThrownBy(() -> reportExportService.exportExcel(request)).isInstanceOf(ValidationException.class).hasMessageContaining("exceeds");
        assertThatThrownBy(() -> reportExportService.exportPdf(request)).isInstanceOf(ValidationException.class).hasMessageContaining("exceeds");
    }

    @Test
    void modelPermissionDenialStopsEveryExportBeforeDataRead() throws Exception {
        ReportEntity report = new ReportEntity();
        report.setTenantId(MetaContext.getCurrentTenantId());
        report.setDsl(new ObjectMapper().writeValueAsString(Map.of("title", "Denied", "dataSources",
                Map.of("rows", Map.of("type", "model", "modelCode", "orders")), "body", List.of())));
        when(reportStorageService.findByPid("denied")).thenReturn(report);
        when(userPermissionService.hasPermission(99L, "model.orders.read")).thenReturn(false);
        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("denied");
        assertThatThrownBy(() -> reportExportService.exportJson(request)).isInstanceOf(com.auraboot.framework.exception.PermissionDeniedException.class);
        assertThatThrownBy(() -> reportExportService.exportExcel(request)).isInstanceOf(com.auraboot.framework.exception.PermissionDeniedException.class);
        assertThatThrownBy(() -> reportExportService.exportPdf(request)).isInstanceOf(com.auraboot.framework.exception.PermissionDeniedException.class);
        org.mockito.Mockito.verifyNoInteractions(dynamicDataService, namedQueryService, auditTrailService);
    }

    private Map<String, Object> nonStaticDataSourceReportDsl() {
        Map<String, Object> modelDataSource = new LinkedHashMap<>();
        modelDataSource.put("type", "model");
        modelDataSource.put("modelCode", "rpt_case_model");
        modelDataSource.put("maxItems", 20);
        modelDataSource.put("sortBy", List.of(Map.of("field", "e2et_order_type", "order", "desc"),
                Map.of("field", "e2et_order_title", "order", "asc")));
        modelDataSource.put("filters", List.of(Map.of(
                "field", "e2et_order_title",
                "operator", "EQ",
                "value", "Model"
        )));

        Map<String, Object> namedQueryDataSource = new LinkedHashMap<>();
        namedQueryDataSource.put("type", "namedQuery");
        namedQueryDataSource.put("queryCode", "rpt_named_cases");
        namedQueryDataSource.put("maxItems", 20);

        Map<String, Object> apiDataSource = new LinkedHashMap<>();
        apiDataSource.put("type", "api");
        apiDataSource.put("endpoint", "/api/datasource/list");
        apiDataSource.put("params", Map.of(
                "datasourceId", "nq:rpt_api_cases",
                "format", "records",
                "maxItems", 20,
                "region", "West"
        ));

        Map<String, Object> dsl = new LinkedHashMap<>();
        dsl.put("$schema", "auraboot://schemas/report/v1");
        dsl.put("version", "1.0.0");
        dsl.put("title", "Operations Data Source Export");
        dsl.put("dataSources", Map.of(
                "modelCases", modelDataSource,
                "namedQueryCases", namedQueryDataSource,
                "apiCases", apiDataSource
        ));
        dsl.put("body", List.of(
                tableBlock("model-table", "Model Cases", "modelCases"),
                tableBlock("named-query-table", "NamedQuery Cases", "namedQueryCases"),
                tableBlock("api-table", "API Cases", "apiCases")
        ));
        return dsl;
    }

    private Map<String, Object> tableBlock(String id, String title, String dataSource) {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("id", id);
        table.put("blockType", "table");
        table.put("title", title);
        table.put("dataSource", dataSource);
        table.put("showHeader", true);
        table.put("columns", List.of(
                Map.of("field", "source", "label", "Source"),
                Map.of("field", "cases", "label", "Cases")
        ));
        return table;
    }
}
