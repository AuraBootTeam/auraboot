package com.auraboot.framework.bi;

import com.auraboot.framework.bi.service.impl.ReportRenderClient;
import com.auraboot.framework.bi.service.impl.ReportRenderProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live golden for the Phase 3 rendering chain (Option A', DDR-2026-06-21):
 * real {@link ReportRenderClient} -> real Node render CLI (tsx) -> real headless
 * Chromium -> PDF, asserted with PDFBox. Proves the JVM↔Node↔browser chain end to
 * end on the real backend code, WITHOUT a database or bootRun (the report DSL is
 * built in-test; DB load is unchanged and covered elsewhere).
 *
 * <p>Guarded: only runs when the Node renderer is available locally (web-admin
 * node_modules/.bin/tsx + cli.ts). On a checkout without web-admin deps installed
 * (e.g. headless CI) it is skipped, so it is a local golden, not a unit gate.
 */
class ReportRenderLiveIT {

    private static Path webAdmin() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        // gradle runs the platform subproject with user.dir = platform/ -> ../web-admin;
        // fall back to ./web-admin when run from the repo root.
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

    @Test
    @EnabledIf("rendererAvailable")
    void rendersRealChartPdf_throughTheRealNodeRenderer() throws Exception {
        ReportRenderProperties props = new ReportRenderProperties();
        props.setEnabled(true);
        props.setCommand(List.of(tsx().toString(), cli().toString()));
        props.setTimeoutSeconds(90);
        ReportRenderClient client = new ReportRenderClient(new ObjectMapper(), props);

        Map<String, Object> reportDsl = Map.of(
                "title", "Live Golden Report",
                "body", List.of(
                        Map.of("blockType", "page-header", "content", "Live Golden — AuraBoot"),
                        Map.of("blockType", "page-footer", "text", "internal-only"),
                        Map.of("blockType", "watermark", "text", "CONFIDENTIAL"),
                        Map.of("blockType", "chart", "title", "Monthly Revenue", "dataSource", "rev",
                                "chartType", "bar", "categoryField", "month", "valueField", "amount"),
                        Map.of("blockType", "table", "title", "Detail", "dataSource", "detail",
                                "columns", List.of(Map.of("field", "name", "label", "Item")))));
        Map<String, List<Map<String, Object>>> dataSets = Map.of(
                "rev", List.of(
                        Map.of("month", "Jan", "amount", 100),
                        Map.of("month", "Feb", "amount", 140),
                        Map.of("month", "Mar", "amount", 120)),
                "detail", List.of(Map.of("name", "Widget A")));

        byte[] pdf = client.renderPdf(reportDsl, dataSets);

        assertThat(pdf).isNotNull();
        assertThat(pdf).startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F', (byte) '-');
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdf))) {
            String text = new PDFTextStripper().getText(document);
            // running header lifted into the PDF (the legacy text path cannot do this)
            assertThat(text).contains("Live Golden — AuraBoot");
            // a real vector chart, NOT the legacy "Category | Value" data-table dump
            assertThat(text).doesNotContain("Category");
        }
    }
}
