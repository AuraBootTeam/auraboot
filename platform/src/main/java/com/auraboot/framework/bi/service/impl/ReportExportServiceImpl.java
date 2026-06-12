package com.auraboot.framework.bi.service.impl;

import com.auraboot.framework.bi.dto.ReportExportFile;
import com.auraboot.framework.bi.dto.ReportExportRequest;
import com.auraboot.framework.bi.service.ReportExportService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final PageSchemaMapper pageSchemaMapper;
    private final ObjectMapper objectMapper;

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
        for (Object blockObject : body) {
            if (!(blockObject instanceof Map<?, ?> rawBlock)) {
                continue;
            }
            Map<String, Object> block = (Map<String, Object>) rawBlock;
            String blockType = stringValue(block.get("blockType"), "");
            switch (blockType) {
                case "table", "grouped-table", "cross-tab" -> {
                    writeTableSheet(workbook, block, dataSets, titleStyle, headerStyle);
                    renderedBlocks++;
                }
                case "stat-card" -> {
                    writeStatCardSheet(workbook, block, dataSets, titleStyle, headerStyle);
                    renderedBlocks++;
                }
                default -> {
                    // Non-tabular visual blocks do not have a meaningful Excel projection yet.
                }
            }
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
            String type = stringValue(dataSource.get("type"), "");
            if ("static".equals(type)) {
                Object rows = firstPresent(dataSource, "data", "rows", "records");
                result.put(key, normalizeRows(rows));
            } else {
                result.put(key, List.of());
            }
        }
        return result;
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
}
