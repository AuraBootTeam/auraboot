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
import java.util.ArrayList;
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

        // 45 CJK rows push the report past one page, so the running header/footer
        // (Chromium per-page header/footer) and computed page numbers can be asserted.
        List<Map<String, Object>> detailRows = new ArrayList<>();
        for (int i = 1; i <= 45; i++) {
            detailRows.add(Map.of("name", "明细项目-" + i));
        }

        Map<String, Object> reportDsl = Map.of(
                "title", "Live Golden Report",
                "body", List.of(
                        // CJK running header → asserts both CJK rendering and per-page repeat
                        Map.of("blockType", "page-header", "content", "运营月报 — AuraBoot 企业版"),
                        Map.of("blockType", "page-footer", "text", "internal-only"),
                        Map.of("blockType", "watermark", "text", "CONFIDENTIAL"),
                        Map.of("blockType", "chart", "title", "Monthly Revenue", "dataSource", "rev",
                                "chartType", "bar", "categoryField", "month", "valueField", "amount",
                                "aggregation", "sum"),
                        Map.of("blockType", "table", "title", "明细", "dataSource", "detail",
                                "columns", List.of(Map.of("field", "name", "label", "项目"))),
                        Map.of("blockType", "stat-card", "title", "KPI", "dataSource", "ops",
                                "valueField", "cases", "aggregation", "sum", "label", "Total Cases"),
                        Map.of("blockType", "grouped-table", "title", "By Owner", "dataSource", "ops",
                                "groupByField", "owner", "columns", List.of(
                                        Map.of("field", "region", "label", "Region"),
                                        Map.of("field", "cases", "label", "Cases"))),
                        Map.of("blockType", "cross-tab", "title", "Matrix", "dataSource", "ops",
                                "rowField", "region", "columnField", "status", "valueField", "cases",
                                "aggregation", "sum"),
                        Map.of("blockType", "barcode", "format", "code128",
                                "staticValue", "OPS-LIVE-2026")));
        Map<String, List<Map<String, Object>>> dataSets = Map.of(
                // duplicate Jan rows exercise the aggregation path (Jan -> 130) end to end
                "rev", List.of(
                        Map.of("month", "Jan", "amount", 100),
                        Map.of("month", "Jan", "amount", 30),
                        Map.of("month", "Feb", "amount", 140),
                        Map.of("month", "Mar", "amount", 120)),
                "detail", detailRows,
                "ops", List.of(
                        Map.of("region", "North", "status", "Open", "owner", "Ops-A", "cases", 12),
                        Map.of("region", "North", "status", "Closed", "owner", "Ops-A", "cases", 3),
                        Map.of("region", "South", "status", "Open", "owner", "Ops-B", "cases", 9)));

        byte[] pdf = client.renderPdf(reportDsl, dataSets);

        // ① valid PDF
        assertThat(pdf).isNotNull();
        assertThat(pdf).startsWith((byte) '%', (byte) 'P', (byte) 'D', (byte) 'F', (byte) '-');
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdf))) {
            int pageCount = document.getNumberOfPages();
            String text = new PDFTextStripper().getText(document);

            // The §5 golden DoD (all 5 assertions, pinned so they cannot silently regress):
            // ② running header repeats on EVERY page (Chromium per-page header — the legacy
            //    text path cannot do this), so the CJK header appears >= pageCount times.
            assertThat(pageCount).as("report must span multiple pages").isGreaterThanOrEqualTo(2);
            assertThat(countOccurrences(text, "运营月报"))
                    .as("running header must repeat on every page")
                    .isGreaterThanOrEqualTo(pageCount);
            // ③ computed page-number footer "第 N / M 页"
            assertThat(text).as("page-number footer")
                    .containsPattern("第\\s*\\d+\\s*/\\s*\\d+\\s*页");
            // ④ CJK renders (real Chromium + CJK fonts) — header and table content
            assertThat(text).contains("运营月报");
            assertThat(text).contains("明细项目-");
            // ⑤ a real vector chart, NOT the legacy "Category | Value" data-table dump
            assertThat(text).doesNotContain("Category");

            // all block types rendered through the real chain
            assertThat(text).contains("Total Cases"); // stat-card
            assertThat(text).contains("Ops-A"); // grouped-table
            assertThat(text).contains("Matrix"); // cross-tab
            assertThat(text).contains("OPS-LIVE-2026"); // barcode value
        }
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
