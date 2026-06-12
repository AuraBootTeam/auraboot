package com.auraboot.framework.bi.service.impl;

import com.auraboot.framework.bi.dto.ReportExportFile;
import com.auraboot.framework.bi.dto.ReportExportRequest;
import com.auraboot.framework.bi.service.ReportExportService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.NamedQueryTestRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Report Designer export renderer backed by the PageSchema extension payload.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportExportServiceImpl implements ReportExportService {

    private static final String REPORT_DSL_EXTENSION_KEY = "reportDsl";
    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final float PDF_MARGIN = 48f;
    private static final float PDF_LINE_HEIGHT = 15f;
    private static final String DATA_SOURCE_STATIC = "static";
    private static final String DATA_SOURCE_MODEL = "model";
    private static final String DATA_SOURCE_TABLE = "table";
    private static final String DATA_SOURCE_NAMED_QUERY = "namedQuery";
    private static final String DATA_SOURCE_API = "api";
    private static final int DEFAULT_EXPORT_ROW_LIMIT = 200;
    private static final int MAX_EXPORT_ROW_LIMIT = 1000;
    private static final Set<String> CANONICAL_API_DATA_SOURCE_ENDPOINTS = Set.of(
            "/api/datasource/list",
            "/api/datasources/list"
    );
    private static final Set<String> DATA_SOURCE_CONTROL_PARAMS = Set.of(
            "datasourceId",
            "dataSourceId",
            "format",
            "maxItems",
            "limit",
            "page",
            "pageNum",
            "pageSize",
            "size",
            "valueField",
            "labelField",
            "searchField",
            "keyword",
            "reportingCurrency"
    );

    private final PageSchemaMapper pageSchemaMapper;
    private final ObjectMapper objectMapper;
    private final DynamicDataService dynamicDataService;
    private final NamedQueryService namedQueryService;

    @Override
    public ReportExportFile exportExcel(ReportExportRequest request) {
        if (request == null || !StringUtils.hasText(request.getReportPid())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "reportPid is required");
        }

        Map<String, Object> reportDsl = loadReportDsl(request.getReportPid());
        String title = stringValue(reportDsl.get("title"), "report");

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle titleStyle = createTitleStyle(workbook);
            CellStyle headerStyle = createHeaderStyle(workbook);
            Map<String, List<Map<String, Object>>> dataSets = resolveDataSets(reportDsl);
            int renderedBlocks = renderBody(workbook, reportDsl, dataSets, titleStyle, headerStyle);

            if (renderedBlocks == 0) {
                Sheet sheet = workbook.createSheet("Report");
                sheet.createRow(0).createCell(0).setCellValue(
                        "No exportable data blocks found in this report.");
                sheet.autoSizeColumn(0);
            }

            workbook.write(output);
            return new ReportExportFile(output.toByteArray(), safeFilename(title) + ".xlsx", XLSX_CONTENT_TYPE);
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to export report as Excel: reportPid={}", request.getReportPid(), e);
            throw new ValidationException(ResponseCode.CommonValidationFailed, "Excel export failed: " + e.getMessage());
        }
    }

    @Override
    public ReportExportFile exportPdf(ReportExportRequest request) {
        if (request == null || !StringUtils.hasText(request.getReportPid())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "reportPid is required");
        }

        Map<String, Object> reportDsl = loadReportDsl(request.getReportPid());
        String title = stringValue(reportDsl.get("title"), "report");

        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Map<String, List<Map<String, Object>>> dataSets = resolveDataSets(reportDsl);
            List<PdfLine> lines = renderPdfLines(reportDsl, dataSets, title);
            writePdfLines(document, lines, resolvePdfPageSize(reportDsl));
            document.save(output);
            return new ReportExportFile(output.toByteArray(), safeFilename(title) + ".pdf", PDF_CONTENT_TYPE);
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to export report as PDF: reportPid={}", request.getReportPid(), e);
            throw new ValidationException(ResponseCode.CommonValidationFailed, "PDF export failed: " + e.getMessage());
        }
    }

    @Override
    public ReportExportFile exportJson(ReportExportRequest request) {
        if (request == null || !StringUtils.hasText(request.getReportPid())) {
            throw new ValidationException(ResponseCode.CommonValidationFailed, "reportPid is required");
        }

        Map<String, Object> reportDsl = loadReportDsl(request.getReportPid());
        String title = stringValue(reportDsl.get("title"), "report");

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("format", "auraboot.report.export.v1");
            payload.put("reportPid", request.getReportPid());
            payload.put("reportDsl", reportDsl);
            payload.put("dataSets", resolveDataSets(reportDsl));
            byte[] bytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
            return new ReportExportFile(bytes, safeFilename(title) + ".report.json", JSON_CONTENT_TYPE);
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to export report as JSON: reportPid={}", request.getReportPid(), e);
            throw new ValidationException(ResponseCode.CommonValidationFailed, "JSON export failed: " + e.getMessage());
        }
    }

    private Map<String, Object> loadReportDsl(String reportPid) {
        PageSchema page = pageSchemaMapper.selectByPid(reportPid);
        if (page == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Report not found: " + reportPid);
        }

        ExtensionBean extension = page.getExtension();
        Object reportDsl = extension != null ? extension.get(REPORT_DSL_EXTENSION_KEY) : null;
        if (reportDsl == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Report DSL not found in page extension: " + reportPid);
        }

        return objectMapper.convertValue(reportDsl, new TypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    private List<PdfLine> renderPdfLines(Map<String, Object> reportDsl,
                                         Map<String, List<Map<String, Object>>> dataSets,
                                         String title) {
        List<PdfLine> lines = new ArrayList<>();
        lines.add(PdfLine.heading(title));

        Object bodyObject = reportDsl.get("body");
        if (!(bodyObject instanceof List<?> body) || body.isEmpty()) {
            lines.add(PdfLine.text("No exportable data blocks found in this report."));
            return lines;
        }

        int renderedBlocks = 0;
        for (Object blockObject : body) {
            if (!(blockObject instanceof Map<?, ?> rawBlock)) {
                continue;
            }

            Map<String, Object> block = (Map<String, Object>) rawBlock;
            String blockType = stringValue(block.get("blockType"), "");
            String blockTitle = stringValue(block.get("title"), blockType.isBlank() ? "Block" : blockType);
            switch (blockType) {
                case "table" -> {
                    appendTablePdfLines(lines, block, dataSets, blockTitle);
                    renderedBlocks++;
                }
                case "grouped-table" -> {
                    appendGroupedTablePdfLines(lines, block, dataSets, blockTitle);
                    renderedBlocks++;
                }
                case "cross-tab" -> {
                    appendCrossTabPdfLines(lines, block, dataSets, blockTitle);
                    renderedBlocks++;
                }
                case "stat-card" -> {
                    lines.add(PdfLine.subheading(blockTitle));
                    lines.add(PdfLine.text(stringValue(block.get("label"), blockTitle) + ": "
                            + formatExportValue(aggregateStat(block, rowsForBlock(block, dataSets)))));
                    renderedBlocks++;
                }
                case "rich-text" -> {
                    lines.add(PdfLine.subheading(blockTitle));
                    String content = stringValue(block.get("content"), "");
                    for (String paragraph : content.split("\\R")) {
                        if (StringUtils.hasText(paragraph)) {
                            lines.add(PdfLine.text(paragraph));
                        }
                    }
                    renderedBlocks++;
                }
                case "chart" -> {
                    appendChartPdfLines(lines, block, dataSets, blockTitle);
                    renderedBlocks++;
                }
                case "barcode", "watermark", "page-header", "page-footer" -> {
                    ReportTextArtifact artifact = textArtifactForBlock(block, dataSets);
                    if (artifact != null) {
                        lines.add(PdfLine.text(artifact.label() + ": " + artifact.value()));
                        renderedBlocks++;
                    }
                }
                default -> {
                    // Unknown visual blocks need richer export semantics before they can be claimed as DONE.
                }
            }
        }

        if (renderedBlocks == 0) {
            lines.add(PdfLine.text("No exportable data blocks found in this report."));
        }
        return lines;
    }

    private void appendTablePdfLines(List<PdfLine> lines,
                                     Map<String, Object> block,
                                     Map<String, List<Map<String, Object>>> dataSets,
                                     String blockTitle) {
        lines.add(PdfLine.subheading(blockTitle));
        List<Map<String, Object>> rows = rowsForBlock(block, dataSets);
        List<ReportColumn> columns = resolveColumns(block, rows);
        if (!columns.isEmpty()) {
            lines.add(PdfLine.text(columns.stream().map(ReportColumn::label).toList()));
        }
        if (rows.isEmpty()) {
            lines.add(PdfLine.text("No data rows"));
            return;
        }
        for (Map<String, Object> row : rows) {
            List<String> values = columns.stream()
                    .map(column -> formatExportValue(row.get(column.field())))
                    .toList();
            lines.add(PdfLine.text(values));
        }
    }

    private void appendGroupedTablePdfLines(List<PdfLine> lines,
                                            Map<String, Object> block,
                                            Map<String, List<Map<String, Object>>> dataSets,
                                            String blockTitle) {
        lines.add(PdfLine.subheading(blockTitle));
        List<Map<String, Object>> rows = rowsForBlock(block, dataSets);
        List<ReportColumn> columns = resolveColumns(block, rows);
        if (!columns.isEmpty() && !Boolean.FALSE.equals(block.get("showHeader"))) {
            lines.add(PdfLine.text(columns.stream().map(ReportColumn::label).toList()));
        }
        if (rows.isEmpty()) {
            lines.add(PdfLine.text("No data rows"));
            return;
        }

        String groupByField = stringValue(block.get("groupByField"), "");
        if (!StringUtils.hasText(groupByField)) {
            appendTablePdfLines(lines, block, dataSets, blockTitle);
            return;
        }

        for (Map.Entry<String, List<Map<String, Object>>> group : groupRows(rows, groupByField).entrySet()) {
            lines.add(PdfLine.text(groupByField + ": " + group.getKey() + " (" + group.getValue().size() + ")"));
            for (Map<String, Object> row : group.getValue()) {
                List<String> values = columns.stream()
                        .map(column -> formatExportValue(row.get(column.field())))
                        .toList();
                lines.add(PdfLine.text(values));
            }
        }
    }

    private void appendCrossTabPdfLines(List<PdfLine> lines,
                                        Map<String, Object> block,
                                        Map<String, List<Map<String, Object>>> dataSets,
                                        String blockTitle) {
        lines.add(PdfLine.subheading(blockTitle));
        CrossTabProjection projection = crossTabProjection(block, rowsForBlock(block, dataSets));
        if (projection.rows().isEmpty() || projection.columns().isEmpty()) {
            lines.add(PdfLine.text("No data rows"));
            return;
        }

        List<String> header = new ArrayList<>();
        header.add(projection.rowField() + " \\ " + projection.columnField());
        header.addAll(projection.columns());
        if (projection.showRowTotal()) {
            header.add("Total");
        }
        lines.add(PdfLine.text(header));

        for (String rowKey : projection.rows()) {
            List<String> values = new ArrayList<>();
            values.add(rowKey);
            for (String columnKey : projection.columns()) {
                values.add(formatExportValue(projection.value(rowKey, columnKey)));
            }
            if (projection.showRowTotal()) {
                values.add(formatExportValue(projection.rowTotal(rowKey)));
            }
            lines.add(PdfLine.text(values));
        }

        if (projection.showColumnTotal()) {
            List<String> values = new ArrayList<>();
            values.add("Total");
            for (String columnKey : projection.columns()) {
                values.add(formatExportValue(projection.columnTotal(columnKey)));
            }
            if (projection.showRowTotal()) {
                values.add(formatExportValue(projection.grandTotal()));
            }
            lines.add(PdfLine.text(values));
        }
    }

    private void appendChartPdfLines(List<PdfLine> lines,
                                     Map<String, Object> block,
                                     Map<String, List<Map<String, Object>>> dataSets,
                                     String blockTitle) {
        lines.add(PdfLine.subheading(blockTitle));
        List<ReportMetric> metrics = aggregateChartMetrics(block, rowsForBlock(block, dataSets));
        if (metrics.isEmpty()) {
            lines.add(PdfLine.text("No chart data"));
            return;
        }
        lines.add(PdfLine.text(List.of("Category", "Value")));
        for (ReportMetric metric : metrics) {
            lines.add(PdfLine.text(List.of(metric.label(), formatExportValue(metric.value()))));
        }
    }

    private void writePdfLines(PDDocument document, List<PdfLine> lines, PDRectangle pageSize) throws java.io.IOException {
        PDPage page = new PDPage(pageSize);
        document.addPage(page);
        PDPageContentStream content = new PDPageContentStream(document, page);
        float y = pageSize.getHeight() - PDF_MARGIN;
        try {
            for (PdfLine line : lines) {
                for (String wrappedLine : wrapPdfLine(line.text())) {
                    if (y < PDF_MARGIN) {
                        content.close();
                        page = new PDPage(pageSize);
                        document.addPage(page);
                        content = new PDPageContentStream(document, page);
                        y = pageSize.getHeight() - PDF_MARGIN;
                    }
                    content.beginText();
                    content.setFont(line.bold() ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA, line.fontSize());
                    content.newLineAtOffset(PDF_MARGIN, y);
                    content.showText(sanitizePdfText(wrappedLine));
                    content.endText();
                    y -= line.lineHeight();
                }
            }
        } finally {
            content.close();
        }
    }

    private List<String> wrapPdfLine(String line) {
        String text = sanitizePdfText(line);
        int maxChars = 100;
        if (text.length() <= maxChars) {
            return List.of(text);
        }
        List<String> result = new ArrayList<>();
        for (int start = 0; start < text.length(); start += maxChars) {
            result.add(text.substring(start, Math.min(text.length(), start + maxChars)));
        }
        return result;
    }

    private String sanitizePdfText(String text) {
        return stringValue(text, "").replaceAll("[^\\x20-\\x7E]", "?");
    }

    @SuppressWarnings("unchecked")
    private PDRectangle resolvePdfPageSize(Map<String, Object> reportDsl) {
        Object pageObject = reportDsl.get("page");
        Map<String, Object> page = pageObject instanceof Map<?, ?> rawPage
                ? (Map<String, Object>) rawPage
                : Map.of();
        String size = stringValue(page.get("size"), "A4").toUpperCase(Locale.ROOT);
        PDRectangle rectangle = switch (size) {
            case "A3" -> PDRectangle.A3;
            case "LETTER" -> PDRectangle.LETTER;
            case "LEGAL" -> PDRectangle.LEGAL;
            default -> PDRectangle.A4;
        };
        String orientation = stringValue(page.get("orientation"), "portrait").toLowerCase(Locale.ROOT);
        if ("landscape".equals(orientation)) {
            return new PDRectangle(rectangle.getHeight(), rectangle.getWidth());
        }
        return rectangle;
    }

    @SuppressWarnings("unchecked")
    private int renderBody(Workbook workbook,
                           Map<String, Object> reportDsl,
                           Map<String, List<Map<String, Object>>> dataSets,
                           CellStyle titleStyle,
                           CellStyle headerStyle) {
        Object bodyObject = reportDsl.get("body");
        if (!(bodyObject instanceof List<?> body)) {
            return 0;
        }

        int renderedBlocks = 0;
        List<ReportTextArtifact> textArtifacts = new ArrayList<>();
        for (Object blockObject : body) {
            if (!(blockObject instanceof Map<?, ?> rawBlock)) {
                continue;
            }
            Map<String, Object> block = (Map<String, Object>) rawBlock;
            String blockType = stringValue(block.get("blockType"), "");
            switch (blockType) {
                case "table" -> {
                    writeTableSheet(workbook, block, dataSets, titleStyle, headerStyle);
                    renderedBlocks++;
                }
                case "grouped-table" -> {
                    writeGroupedTableSheet(workbook, block, dataSets, titleStyle, headerStyle);
                    renderedBlocks++;
                }
                case "cross-tab" -> {
                    writeCrossTabSheet(workbook, block, dataSets, titleStyle, headerStyle);
                    renderedBlocks++;
                }
                case "stat-card" -> {
                    writeStatCardSheet(workbook, block, dataSets, titleStyle, headerStyle);
                    renderedBlocks++;
                }
                case "rich-text" -> {
                    writeRichTextSheet(workbook, block, titleStyle);
                    renderedBlocks++;
                }
                case "chart" -> {
                    writeChartSheet(workbook, block, dataSets, titleStyle, headerStyle);
                    renderedBlocks++;
                }
                case "barcode", "watermark", "page-header", "page-footer" -> {
                    ReportTextArtifact artifact = textArtifactForBlock(block, dataSets);
                    if (artifact != null) {
                        textArtifacts.add(artifact);
                    }
                }
                default -> {
                    // Unknown visual blocks need richer export semantics before they can be claimed as DONE.
                }
            }
        }
        if (!textArtifacts.isEmpty()) {
            writeTextArtifactsSheet(workbook, textArtifacts, titleStyle);
            renderedBlocks++;
        }
        return renderedBlocks;
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<Map<String, Object>>> resolveDataSets(Map<String, Object> reportDsl) {
        Object dataSourcesObject = reportDsl.get("dataSources");
        if (!(dataSourcesObject instanceof Map<?, ?> dataSources)) {
            return Map.of();
        }

        Map<String, List<Map<String, Object>>> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : dataSources.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof Map<?, ?> rawDataSource)) {
                continue;
            }
            Map<String, Object> dataSource = (Map<String, Object>) rawDataSource;
            result.put(key, resolveDataSourceRows(dataSource));
        }
        return result;
    }

    private List<Map<String, Object>> resolveDataSourceRows(Map<String, Object> dataSource) {
        String type = stringValue(dataSource.get("type"), "");
        Object inlineRows = firstPresent(dataSource, "data", "rows", "records");
        if (!StringUtils.hasText(type) && inlineRows != null) {
            return normalizeRows(inlineRows);
        }

        return switch (type) {
            case DATA_SOURCE_STATIC -> normalizeRows(inlineRows);
            case DATA_SOURCE_MODEL, DATA_SOURCE_TABLE -> resolveModelRows(dataSource);
            case DATA_SOURCE_NAMED_QUERY -> {
                String queryCode = stringValue(firstPresent(dataSource, "queryCode", "code", "namedQueryCode"), "");
                yield resolveNamedQueryRows(queryCode, dataSource, dataSourceParams(dataSource));
            }
            case DATA_SOURCE_API -> resolveApiRows(dataSource);
            default -> throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Unsupported report dataSource type: " + type);
        };
    }

    private List<Map<String, Object>> resolveModelRows(Map<String, Object> dataSource) {
        String modelCode = stringValue(firstPresent(dataSource, "modelCode", "model", "entityCode"), "");
        if (!StringUtils.hasText(modelCode)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Report model dataSource requires modelCode");
        }

        Map<String, Object> params = dataSourceParams(dataSource);
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(resolveRowLimit(dataSource, params))
                .keyword(stringValue(firstPresent(dataSource, "keyword"), null))
                .conditions(resolveModelConditions(dataSource, params))
                .extraParams(removeControlParams(params))
                .build();
        PaginationResult<Map<String, Object>> result = dynamicDataService.list(modelCode, request);
        return result == null ? List.of() : normalizeRows(result.getRecords());
    }

    private List<Map<String, Object>> resolveNamedQueryRows(String queryCode,
                                                           Map<String, Object> dataSource,
                                                           Map<String, Object> params) {
        if (!StringUtils.hasText(queryCode)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Report namedQuery dataSource requires queryCode");
        }

        NamedQueryTestRequest request = new NamedQueryTestRequest();
        request.setPage(1);
        request.setSize(resolveRowLimit(dataSource, params));
        request.setExecuteQuery(true);
        request.setParameters(removeControlParams(params));
        PaginationResult<Map<String, Object>> result = namedQueryService.executeQuery(queryCode, request);
        return result == null ? List.of() : normalizeRows(result.getRecords());
    }

    private List<Map<String, Object>> resolveApiRows(Map<String, Object> dataSource) {
        String endpoint = stringValue(firstPresent(dataSource, "endpoint", "url"), "");
        if (!StringUtils.hasText(endpoint)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Report api dataSource requires endpoint");
        }

        String endpointPath = endpointPath(endpoint);
        if (!CANONICAL_API_DATA_SOURCE_ENDPOINTS.contains(endpointPath)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Report api dataSource export supports only /api/datasource/list");
        }

        Map<String, Object> params = new LinkedHashMap<>(endpointQueryParams(endpoint));
        params.putAll(dataSourceParams(dataSource));
        String datasourceId = stringValue(firstPresent(params, "datasourceId", "dataSourceId"), "");
        if (!datasourceId.startsWith("nq:") || datasourceId.length() <= 3) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Report api dataSource export requires datasourceId=nq:{queryCode}");
        }
        return resolveNamedQueryRows(datasourceId.substring(3), dataSource, params);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataSourceParams(Map<String, Object> dataSource) {
        Object paramsObject = firstPresent(dataSource, "params", "parameters", "query");
        if (!(paramsObject instanceof Map<?, ?> rawParams)) {
            return Map.of();
        }
        return new LinkedHashMap<>(toStringObjectMap((Map<Object, Object>) rawParams));
    }

    private Map<String, Object> removeControlParams(Map<String, Object> params) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!DATA_SOURCE_CONTROL_PARAMS.contains(entry.getKey())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private List<QueryCondition> resolveModelConditions(Map<String, Object> dataSource,
                                                        Map<String, Object> params) {
        Object filters = firstPresent(dataSource, "filters", "conditions");
        if (filters == null && params != null) {
            filters = firstPresent(params, "filters", "conditions");
        }
        if (filters == null) {
            return null;
        }

        try {
            List<QueryCondition> conditions;
            if (filters instanceof String filterText) {
                if (!StringUtils.hasText(filterText)) {
                    return null;
                }
                conditions = objectMapper.readValue(filterText, new TypeReference<List<QueryCondition>>() {});
            } else if (filters instanceof List<?>) {
                conditions = objectMapper.convertValue(filters, new TypeReference<List<QueryCondition>>() {});
            } else if (filters instanceof Map<?, ?>) {
                QueryCondition condition = objectMapper.convertValue(filters, QueryCondition.class);
                conditions = List.of(condition);
            } else {
                throw new IllegalArgumentException("unsupported filters payload");
            }
            return conditions.isEmpty() ? null : conditions;
        } catch (Exception e) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Report model dataSource filters must be a QueryCondition array");
        }
    }

    private int resolveRowLimit(Map<String, Object> dataSource, Map<String, Object> params) {
        Object rawLimit = firstPresent(dataSource, "maxItems", "limit", "pageSize", "size");
        if (rawLimit == null && params != null) {
            rawLimit = firstPresent(params, "maxItems", "limit", "pageSize", "size");
        }
        if (rawLimit == null) {
            return DEFAULT_EXPORT_ROW_LIMIT;
        }
        try {
            int parsed = Integer.parseInt(rawLimit.toString());
            return Math.max(1, Math.min(parsed, MAX_EXPORT_ROW_LIMIT));
        } catch (NumberFormatException e) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Report dataSource row limit must be a number");
        }
    }

    private String endpointPath(String endpoint) {
        URI uri = URI.create(endpoint);
        if (uri.isAbsolute()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Report api dataSource export requires a relative endpoint");
        }
        return uri.getPath();
    }

    private Map<String, Object> endpointQueryParams(String endpoint) {
        URI uri = URI.create(endpoint);
        String rawQuery = uri.getRawQuery();
        if (!StringUtils.hasText(rawQuery)) {
            return Map.of();
        }

        Map<String, Object> params = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            if (!StringUtils.hasText(pair)) {
                continue;
            }
            String[] keyValue = pair.split("=", 2);
            String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
            if (StringUtils.hasText(key)) {
                String value = keyValue.length > 1
                        ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8)
                        : "";
                params.put(key, value);
            }
        }
        return params;
    }

    private void writeTableSheet(Workbook workbook,
                                 Map<String, Object> block,
                                 Map<String, List<Map<String, Object>>> dataSets,
                                 CellStyle titleStyle,
                                 CellStyle headerStyle) {
        String title = stringValue(block.get("title"), "Table");
        List<Map<String, Object>> rows = rowsForBlock(block, dataSets);
        List<ReportColumn> columns = resolveColumns(block, rows);
        Sheet sheet = workbook.createSheet(uniqueSheetName(workbook, title));

        int rowIndex = 0;
        if (StringUtils.hasText(title)) {
            Row titleRow = sheet.createRow(rowIndex++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(title);
            titleCell.setCellStyle(titleStyle);
        }

        boolean showHeader = !Boolean.FALSE.equals(block.get("showHeader"));
        if (showHeader && !columns.isEmpty()) {
            Row headerRow = sheet.createRow(rowIndex++);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(columns.get(i).label());
                cell.setCellStyle(headerStyle);
            }
        }

        for (Map<String, Object> rowData : rows) {
            Row row = sheet.createRow(rowIndex++);
            for (int i = 0; i < columns.size(); i++) {
                writeCell(row.createCell(i), rowData.get(columns.get(i).field()));
            }
        }

        if (rows.isEmpty()) {
            Row emptyRow = sheet.createRow(rowIndex);
            emptyRow.createCell(0).setCellValue("No data rows");
        }

        for (int i = 0; i < Math.max(columns.size(), 1); i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void writeGroupedTableSheet(Workbook workbook,
                                        Map<String, Object> block,
                                        Map<String, List<Map<String, Object>>> dataSets,
                                        CellStyle titleStyle,
                                        CellStyle headerStyle) {
        String title = stringValue(block.get("title"), "Grouped Table");
        List<Map<String, Object>> rows = rowsForBlock(block, dataSets);
        List<ReportColumn> columns = resolveColumns(block, rows);
        String groupByField = stringValue(block.get("groupByField"), "");
        if (!StringUtils.hasText(groupByField)) {
            writeTableSheet(workbook, block, dataSets, titleStyle, headerStyle);
            return;
        }

        Sheet sheet = workbook.createSheet(uniqueSheetName(workbook, title));
        int rowIndex = 0;
        Row titleRow = sheet.createRow(rowIndex++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(titleStyle);

        if (!Boolean.FALSE.equals(block.get("showHeader")) && !columns.isEmpty()) {
            Row headerRow = sheet.createRow(rowIndex++);
            for (int i = 0; i < columns.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(columns.get(i).label());
                cell.setCellStyle(headerStyle);
            }
        }

        if (rows.isEmpty()) {
            sheet.createRow(rowIndex).createCell(0).setCellValue("No data rows");
        } else {
            for (Map.Entry<String, List<Map<String, Object>>> group : groupRows(rows, groupByField).entrySet()) {
                Row groupRow = sheet.createRow(rowIndex++);
                Cell groupCell = groupRow.createCell(0);
                groupCell.setCellValue(groupByField + ": " + group.getKey() + " (" + group.getValue().size() + ")");
                groupCell.setCellStyle(headerStyle);

                for (Map<String, Object> rowData : group.getValue()) {
                    Row row = sheet.createRow(rowIndex++);
                    for (int i = 0; i < columns.size(); i++) {
                        writeCell(row.createCell(i), rowData.get(columns.get(i).field()));
                    }
                }
            }
        }

        for (int i = 0; i < Math.max(columns.size(), 1); i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void writeCrossTabSheet(Workbook workbook,
                                    Map<String, Object> block,
                                    Map<String, List<Map<String, Object>>> dataSets,
                                    CellStyle titleStyle,
                                    CellStyle headerStyle) {
        String title = stringValue(block.get("title"), "Cross Tab");
        CrossTabProjection projection = crossTabProjection(block, rowsForBlock(block, dataSets));
        Sheet sheet = workbook.createSheet(uniqueSheetName(workbook, title));
        int rowIndex = 0;

        Row titleRow = sheet.createRow(rowIndex++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(titleStyle);

        if (projection.rows().isEmpty() || projection.columns().isEmpty()) {
            sheet.createRow(rowIndex).createCell(0).setCellValue("No data rows");
            sheet.autoSizeColumn(0);
            return;
        }

        Row headerRow = sheet.createRow(rowIndex++);
        Cell firstHeaderCell = headerRow.createCell(0);
        firstHeaderCell.setCellValue(projection.rowField() + " \\ " + projection.columnField());
        firstHeaderCell.setCellStyle(headerStyle);
        for (int i = 0; i < projection.columns().size(); i++) {
            Cell cell = headerRow.createCell(i + 1);
            cell.setCellValue(projection.columns().get(i));
            cell.setCellStyle(headerStyle);
        }
        if (projection.showRowTotal()) {
            Cell totalCell = headerRow.createCell(projection.columns().size() + 1);
            totalCell.setCellValue("Total");
            totalCell.setCellStyle(headerStyle);
        }

        for (String rowKey : projection.rows()) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(rowKey);
            for (int i = 0; i < projection.columns().size(); i++) {
                writeCell(row.createCell(i + 1), projection.value(rowKey, projection.columns().get(i)));
            }
            if (projection.showRowTotal()) {
                writeCell(row.createCell(projection.columns().size() + 1), projection.rowTotal(rowKey));
            }
        }

        if (projection.showColumnTotal()) {
            Row totalRow = sheet.createRow(rowIndex);
            Cell labelCell = totalRow.createCell(0);
            labelCell.setCellValue("Total");
            labelCell.setCellStyle(headerStyle);
            for (int i = 0; i < projection.columns().size(); i++) {
                writeCell(totalRow.createCell(i + 1), projection.columnTotal(projection.columns().get(i)));
            }
            if (projection.showRowTotal()) {
                writeCell(totalRow.createCell(projection.columns().size() + 1), projection.grandTotal());
            }
        }

        int columnCount = projection.columns().size() + (projection.showRowTotal() ? 2 : 1);
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void writeStatCardSheet(Workbook workbook,
                                    Map<String, Object> block,
                                    Map<String, List<Map<String, Object>>> dataSets,
                                    CellStyle titleStyle,
                                    CellStyle headerStyle) {
        String title = stringValue(block.get("title"), "Stat Card");
        Sheet sheet = workbook.createSheet(uniqueSheetName(workbook, title));

        Row titleRow = sheet.createRow(0);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(titleStyle);

        Row headerRow = sheet.createRow(1);
        Cell metricHeader = headerRow.createCell(0);
        metricHeader.setCellValue("Metric");
        metricHeader.setCellStyle(headerStyle);
        Cell valueHeader = headerRow.createCell(1);
        valueHeader.setCellValue("Value");
        valueHeader.setCellStyle(headerStyle);

        Row valueRow = sheet.createRow(2);
        valueRow.createCell(0).setCellValue(stringValue(block.get("label"), title));
        writeCell(valueRow.createCell(1), aggregateStat(block, rowsForBlock(block, dataSets)));
        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
    }

    private void writeRichTextSheet(Workbook workbook, Map<String, Object> block, CellStyle titleStyle) {
        String title = stringValue(block.get("title"), "Rich Text");
        Sheet sheet = workbook.createSheet(uniqueSheetName(workbook, title));
        Row titleRow = sheet.createRow(0);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(titleStyle);

        String content = stringValue(block.get("content"), "");
        int rowIndex = 1;
        for (String paragraph : content.split("\\R")) {
            if (StringUtils.hasText(paragraph)) {
                sheet.createRow(rowIndex++).createCell(0).setCellValue(paragraph);
            }
        }
        if (rowIndex == 1) {
            sheet.createRow(rowIndex).createCell(0).setCellValue("No rich text content");
        }
        sheet.autoSizeColumn(0);
    }

    private void writeChartSheet(Workbook workbook,
                                 Map<String, Object> block,
                                 Map<String, List<Map<String, Object>>> dataSets,
                                 CellStyle titleStyle,
                                 CellStyle headerStyle) {
        String title = stringValue(block.get("title"), "Chart Data");
        Sheet sheet = workbook.createSheet(uniqueSheetName(workbook, title));
        Row titleRow = sheet.createRow(0);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(title);
        titleCell.setCellStyle(titleStyle);

        Row headerRow = sheet.createRow(1);
        Cell categoryHeader = headerRow.createCell(0);
        categoryHeader.setCellValue("Category");
        categoryHeader.setCellStyle(headerStyle);
        Cell valueHeader = headerRow.createCell(1);
        valueHeader.setCellValue("Value");
        valueHeader.setCellStyle(headerStyle);

        List<ReportMetric> metrics = aggregateChartMetrics(block, rowsForBlock(block, dataSets));
        if (metrics.isEmpty()) {
            sheet.createRow(2).createCell(0).setCellValue("No chart data");
        } else {
            int rowIndex = 2;
            for (ReportMetric metric : metrics) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(metric.label());
                writeCell(row.createCell(1), metric.value());
            }
        }
        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
    }

    private void writeTextArtifactsSheet(Workbook workbook,
                                         List<ReportTextArtifact> artifacts,
                                         CellStyle titleStyle) {
        Sheet sheet = workbook.createSheet(uniqueSheetName(workbook, "Report Text"));
        Row titleRow = sheet.createRow(0);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("Report Text");
        titleCell.setCellStyle(titleStyle);

        for (int i = 0; i < artifacts.size(); i++) {
            ReportTextArtifact artifact = artifacts.get(i);
            Row row = sheet.createRow(i + 1);
            row.createCell(0).setCellValue(artifact.label());
            row.createCell(1).setCellValue(artifact.value());
        }
        sheet.autoSizeColumn(0);
        sheet.autoSizeColumn(1);
    }

    @SuppressWarnings("unchecked")
    private List<ReportColumn> resolveColumns(Map<String, Object> block, List<Map<String, Object>> rows) {
        Object columnsObject = block.get("columns");
        List<ReportColumn> columns = new ArrayList<>();
        if (columnsObject instanceof List<?> rawColumns) {
            for (Object columnObject : rawColumns) {
                if (columnObject instanceof Map<?, ?> rawColumn) {
                    Map<String, Object> column = (Map<String, Object>) rawColumn;
                    String field = stringValue(column.get("field"), "");
                    if (StringUtils.hasText(field)) {
                        columns.add(new ReportColumn(field, stringValue(column.get("label"), field)));
                    }
                }
            }
        }

        if (!columns.isEmpty() || rows.isEmpty()) {
            return columns;
        }

        Set<String> fields = new LinkedHashSet<>(rows.get(0).keySet());
        for (String field : fields) {
            columns.add(new ReportColumn(field, field));
        }
        return columns;
    }

    private Map<String, List<Map<String, Object>>> groupRows(List<Map<String, Object>> rows, String groupByField) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String groupKey = stringValue(row.get(groupByField), "Other");
            groups.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(row);
        }
        return groups;
    }

    private CrossTabProjection crossTabProjection(Map<String, Object> block, List<Map<String, Object>> rows) {
        String rowField = stringValue(block.get("rowField"), "Row");
        String columnField = stringValue(block.get("columnField"), "Column");
        String valueField = stringValue(block.get("valueField"), "");
        String aggregation = stringValue(block.get("aggregation"), "sum").toLowerCase(Locale.ROOT);
        boolean showRowTotal = !Boolean.FALSE.equals(block.get("showRowTotal"));
        boolean showColumnTotal = !Boolean.FALSE.equals(block.get("showColumnTotal"));

        Set<String> rowKeys = new LinkedHashSet<>();
        Set<String> columnKeys = new LinkedHashSet<>();
        Map<String, List<Double>> values = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String rowKey = stringValue(row.get(rowField), "Other");
            String columnKey = stringValue(row.get(columnField), "Other");
            Object rawValue = row.get(valueField);
            double value = rawValue instanceof Number number ? number.doubleValue() : 0d;
            rowKeys.add(rowKey);
            columnKeys.add(columnKey);
            values.computeIfAbsent(rowKey + "\u0000" + columnKey, ignored -> new ArrayList<>()).add(value);
        }

        List<String> sortedRows = rowKeys.stream().sorted().toList();
        List<String> sortedColumns = columnKeys.stream().sorted().toList();
        Map<String, Double> aggregated = new HashMap<>();
        for (Map.Entry<String, List<Double>> entry : values.entrySet()) {
            aggregated.put(entry.getKey(), aggregateNumbers(entry.getValue(), aggregation));
        }
        return new CrossTabProjection(rowField, columnField, sortedRows, sortedColumns,
                aggregated, showRowTotal, showColumnTotal);
    }

    private List<ReportMetric> aggregateChartMetrics(Map<String, Object> block, List<Map<String, Object>> rows) {
        String categoryField = stringValue(block.get("categoryField"), "");
        String valueField = stringValue(block.get("valueField"), "");
        String aggregation = stringValue(block.get("aggregation"), "sum").toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(categoryField)) {
            return List.of();
        }

        Map<String, List<Double>> grouped = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String category = stringValue(row.get(categoryField), "Other");
            Object rawValue = row.get(valueField);
            double value = rawValue instanceof Number number ? number.doubleValue() : 0d;
            grouped.computeIfAbsent(category, ignored -> new ArrayList<>()).add(value);
        }

        return grouped.entrySet().stream()
                .map(entry -> new ReportMetric(entry.getKey(), aggregateNumbers(entry.getValue(), aggregation)))
                .sorted((left, right) -> left.label().compareTo(right.label()))
                .toList();
    }

    private double aggregateNumbers(List<Double> values, String aggregation) {
        if (values.isEmpty()) {
            return 0d;
        }
        return switch (aggregation) {
            case "avg" -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0d);
            case "count" -> values.size();
            case "min" -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0d);
            case "max" -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0d);
            default -> values.stream().mapToDouble(Double::doubleValue).sum();
        };
    }

    private List<Map<String, Object>> rowsForBlock(Map<String, Object> block,
                                                   Map<String, List<Map<String, Object>>> dataSets) {
        String dataSource = stringValue(block.get("dataSource"), "");
        if (!StringUtils.hasText(dataSource)) {
            return List.of();
        }
        return dataSets.getOrDefault(dataSource, List.of());
    }

    private Object aggregateStat(Map<String, Object> block, List<Map<String, Object>> rows) {
        String aggregation = stringValue(block.get("aggregation"), "count").toLowerCase(Locale.ROOT);
        String valueField = stringValue(block.get("valueField"), "");
        if ("count".equals(aggregation)) {
            return rows.size();
        }

        List<Double> values = rows.stream()
                .map(row -> row.get(valueField))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::doubleValue)
                .toList();
        if (values.isEmpty()) {
            return 0;
        }

        return switch (aggregation) {
            case "avg" -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            case "min" -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            case "max" -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            default -> values.stream().mapToDouble(Double::doubleValue).sum();
        };
    }

    private ReportTextArtifact textArtifactForBlock(Map<String, Object> block,
                                                    Map<String, List<Map<String, Object>>> dataSets) {
        String blockType = stringValue(block.get("blockType"), "");
        String label = switch (blockType) {
            case "page-header" -> "Page Header";
            case "page-footer" -> "Page Footer";
            case "watermark" -> "Watermark";
            case "barcode" -> "Barcode";
            default -> "";
        };
        if (!StringUtils.hasText(label)) {
            return null;
        }

        String value = switch (blockType) {
            case "barcode" -> resolveBarcodeValue(block, rowsForBlock(block, dataSets));
            case "watermark" -> stringValue(block.get("text"), "");
            default -> stringValue(firstPresent(block, "content", "text", "title"), "");
        };
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return new ReportTextArtifact(label, value);
    }

    private String resolveBarcodeValue(Map<String, Object> block, List<Map<String, Object>> rows) {
        String staticValue = stringValue(block.get("staticValue"), "");
        if (StringUtils.hasText(staticValue)) {
            return staticValue;
        }
        String field = stringValue(block.get("field"), "");
        if (!StringUtils.hasText(field) || rows.isEmpty()) {
            return "";
        }
        return stringValue(rows.get(0).get(field), "");
    }

    private List<Map<String, Object>> normalizeRows(Object rowsObject) {
        if (rowsObject instanceof Map<?, ?> rowsMap) {
            Object nestedRows = firstPresent(toStringObjectMap(rowsMap), "records", "rows", "data");
            if (nestedRows != null && nestedRows != rowsObject) {
                return normalizeRows(nestedRows);
            }
            return List.of(new LinkedHashMap<>(toStringObjectMap(rowsMap)));
        }
        if (!(rowsObject instanceof List<?> rows)) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof Map<?, ?> rawRow) {
                result.add(new LinkedHashMap<>(toStringObjectMap(rawRow)));
            }
        }
        return result;
    }

    private Map<String, Object> toStringObjectMap(Map<?, ?> rawMap) {
        Map<String, Object> result = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> {
            if (key != null) {
                result.put(key.toString(), value);
            }
        });
        return result;
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private void writeCell(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else {
            cell.setCellValue(value.toString());
        }
    }

    private CellStyle createTitleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        style.setFont(font);
        return style;
    }

    private CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? fallback : text;
    }

    private String formatExportValue(Object value) {
        if (value instanceof Number number) {
            double doubleValue = number.doubleValue();
            if (Math.rint(doubleValue) == doubleValue) {
                return Long.toString(Math.round(doubleValue));
            }
            return Double.toString(doubleValue);
        }
        return stringValue(value, "");
    }

    private String uniqueSheetName(Workbook workbook, String preferredName) {
        String baseName = WorkbookUtil.createSafeSheetName(stringValue(preferredName, "Report"));
        if (baseName.length() > 31) {
            baseName = baseName.substring(0, 31);
        }
        String candidate = baseName;
        int suffix = 2;
        while (workbook.getSheet(candidate) != null) {
            String postfix = " " + suffix++;
            int maxBaseLength = Math.max(1, 31 - postfix.length());
            candidate = baseName.substring(0, Math.min(baseName.length(), maxBaseLength)) + postfix;
        }
        return candidate;
    }

    private String safeFilename(String title) {
        String candidate = stringValue(title, "report").replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
        return candidate.isEmpty() ? "report" : candidate;
    }

    private record ReportColumn(String field, String label) {
    }

    private record ReportMetric(String label, double value) {
    }

    private record ReportTextArtifact(String label, String value) {
    }

    private record CrossTabProjection(String rowField,
                                      String columnField,
                                      List<String> rows,
                                      List<String> columns,
                                      Map<String, Double> values,
                                      boolean showRowTotal,
                                      boolean showColumnTotal) {
        double value(String rowKey, String columnKey) {
            return values.getOrDefault(rowKey + "\u0000" + columnKey, 0d);
        }

        double rowTotal(String rowKey) {
            return columns.stream().mapToDouble(columnKey -> value(rowKey, columnKey)).sum();
        }

        double columnTotal(String columnKey) {
            return rows.stream().mapToDouble(rowKey -> value(rowKey, columnKey)).sum();
        }

        double grandTotal() {
            return rows.stream().mapToDouble(this::rowTotal).sum();
        }
    }

    private record PdfLine(String text, boolean bold, float fontSize, float lineHeight) {
        static PdfLine heading(String text) {
            return new PdfLine(text, true, 16f, PDF_LINE_HEIGHT + 6f);
        }

        static PdfLine subheading(String text) {
            return new PdfLine(text, true, 12f, PDF_LINE_HEIGHT + 3f);
        }

        static PdfLine text(String text) {
            return new PdfLine(text, false, 10f, PDF_LINE_HEIGHT);
        }

        static PdfLine text(List<String> cells) {
            return text(String.join(" | ", cells));
        }
    }
}
