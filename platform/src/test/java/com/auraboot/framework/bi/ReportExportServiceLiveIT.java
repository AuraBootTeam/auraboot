package com.auraboot.framework.bi;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bi.dto.ReportExportFile;
import com.auraboot.framework.bi.dto.ReportExportRequest;
import com.auraboot.framework.bi.service.ReportStorageService;
import com.auraboot.framework.bi.service.impl.ReportExportServiceImpl;
import com.auraboot.framework.bi.service.impl.ReportRenderClient;
import com.auraboot.framework.bi.service.impl.ReportRenderProperties;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.meta.service.impl.AuditTrailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Full-stack service golden for Phase 3 (Option A', DDR-2026-06-21): the REAL
 * {@link ReportExportServiceImpl#exportPdf} path driven through the REAL Node
 * renderer (renderer.command = the cli.ts entrypoint) -> real headless Chromium
 * -> PDF. Exercises loadReportDsl -> resolveDataSets -> renderPdf ->
 * ReportRenderClient -> subprocess, asserting the WYSIWYG output (running header
 * + real chart) rather than the legacy PDFBox text path.
 *
 * <p>DAOs are mocked (no database) so this stays safe and self-contained; the
 * unchanged DB read-switch is covered by ReportExportServiceReadSwitchIT.
 * Guarded: skipped when the local web-admin renderer deps are absent.
 */
@ExtendWith(MockitoExtension.class)
class ReportExportServiceLiveIT {

    @Mock
    private PageSchemaMapper pageSchemaMapper;
    @Mock
    private DynamicDataService dynamicDataService;
    @Mock
    private NamedQueryService namedQueryService;
    @Mock
    private ReportStorageService reportStorageService;
    @Mock
    private AuditTrailService auditTrailService;

    private static Path webAdmin() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path sibling = cwd.resolveSibling("web-admin");
        return Files.exists(sibling) ? sibling : cwd.resolve("web-admin");
    }

    private static Path tsx() {
        return webAdmin().resolve("node_modules/.bin/tsx");
    }

    private static Path cli() {
        return webAdmin().resolve("app/framework/smart/report-export/cli.ts");
    }

    static boolean rendererAvailable() {
        return Files.isExecutable(tsx()) && Files.exists(cli());
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @EnabledIf("rendererAvailable")
    void exportPdf_throughRealRenderer_producesWysiwygPdf() throws Exception {
        ReportRenderProperties props = new ReportRenderProperties();
        props.setEnabled(true);
        props.setCommand(List.of(tsx().toString(), cli().toString()));
        props.setTimeoutSeconds(90);
        ReportRenderClient client = new ReportRenderClient(new ObjectMapper(), props);

        ReportExportServiceImpl service = new ReportExportServiceImpl(
                pageSchemaMapper, new ObjectMapper(), dynamicDataService, namedQueryService,
                reportStorageService, auditTrailService, client);
        MetaContext.setContext(7L, 99L, "user-pid", "tester");

        PageSchema page = new PageSchema();
        ExtensionBean extension = new ExtensionBean();
        extension.setDynamicProperty("reportDsl", chartReportDsl());
        page.setExtension(extension);
        when(pageSchemaMapper.selectByPid("rpt-live-service")).thenReturn(page);

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-live-service");

        ReportExportFile file = service.exportPdf(request);

        assertThat(file.getContentType()).isEqualTo("application/pdf");
        assertThat(file.getBytes()).startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F', (byte) '-');
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(file.getBytes()))) {
            String text = new PDFTextStripper().getText(document);
            // running header lifted into the PDF — proves the WYSIWYG renderer path ran
            assertThat(text).contains("Live Service Report");
            // a real vector chart, NOT the legacy "Category | Value" text dump
            assertThat(text).doesNotContain("Category");
        }
    }

    private Map<String, Object> chartReportDsl() {
        Map<String, Object> dataSource = new LinkedHashMap<>();
        dataSource.put("type", "static");
        dataSource.put("data", List.of(
                Map.of("month", "Jan", "amount", 100),
                Map.of("month", "Feb", "amount", 140)));

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("blockType", "page-header");
        header.put("content", "Live Service Report");

        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("blockType", "chart");
        chart.put("title", "Revenue");
        chart.put("dataSource", "rev");
        chart.put("chartType", "bar");
        chart.put("categoryField", "month");
        chart.put("valueField", "amount");
        chart.put("aggregation", "sum");

        Map<String, Object> dsl = new LinkedHashMap<>();
        dsl.put("title", "Live Service Export");
        dsl.put("dataSources", Map.of("rev", dataSource));
        dsl.put("body", List.of(header, chart));
        return dsl;
    }
}
