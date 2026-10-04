package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.security.CsvSafetyUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/** Serializes authorized export rows and decodes import files; permission checks stay in the service. */
@RequiredArgsConstructor
final class DynamicDataFileCodec {
    private final ObjectMapper objectMapper;
    Path exportAsExcel(List<Map<String, Object>> data, List<String> fields,
                               Map<String, String> fieldLabelMap, String fileName, Boolean includeHeader)
            throws IOException {
        Path tempFile = Files.createTempFile(fileName, ".xlsx");
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
            org.apache.poi.xssf.usermodel.XSSFSheet sheet = workbook.createSheet("Data");

            // Create default font with Chinese support
            org.apache.poi.xssf.usermodel.XSSFFont defaultFont = workbook.createFont();
            defaultFont.setFontName("Arial Unicode MS");
            defaultFont.setFontHeightInPoints((short) 11);

            // Create default cell style
            org.apache.poi.xssf.usermodel.XSSFCellStyle defaultStyle = workbook.createCellStyle();
            defaultStyle.setFont(defaultFont);

            int rowNum = 0;

            // Write header
            if (!Boolean.FALSE.equals(includeHeader)) {
                org.apache.poi.xssf.usermodel.XSSFRow headerRow = sheet.createRow(rowNum++);
                // Create header style
                org.apache.poi.xssf.usermodel.XSSFCellStyle headerStyle = workbook.createCellStyle();
                org.apache.poi.xssf.usermodel.XSSFFont headerFont = workbook.createFont();
                headerFont.setFontName("Arial Unicode MS");
                headerFont.setFontHeightInPoints((short) 11);
                headerFont.setBold(true);
                headerStyle.setFont(headerFont);
                headerStyle.setFillForegroundColor(org.apache.poi.ss.usermodel.IndexedColors.GREY_25_PERCENT.getIndex());
                headerStyle.setFillPattern(org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND);

                for (int i = 0; i < fields.size(); i++) {
                    org.apache.poi.xssf.usermodel.XSSFCell cell = headerRow.createCell(i);
                    cell.setCellValue(fieldLabelMap != null
                            ? fieldLabelMap.getOrDefault(fields.get(i), fields.get(i))
                            : fields.get(i));
                    cell.setCellStyle(headerStyle);
                }
            }

            // Write data rows
            for (Map<String, Object> row : data) {
                org.apache.poi.xssf.usermodel.XSSFRow dataRow = sheet.createRow(rowNum++);
                for (int i = 0; i < fields.size(); i++) {
                    org.apache.poi.xssf.usermodel.XSSFCell cell = dataRow.createCell(i);
                    cell.setCellStyle(defaultStyle);
                    Object val = row.get(fields.get(i));
                    if (val != null) {
                        if (val instanceof Number) {
                            cell.setCellValue(((Number) val).doubleValue());
                        } else if (val instanceof Boolean) {
                            cell.setCellValue((Boolean) val);
                        } else if (val instanceof java.util.Date) {
                            cell.setCellValue((java.util.Date) val);
                        } else if (val instanceof java.time.LocalDateTime) {
                            cell.setCellValue(val.toString());
                        } else if (val instanceof java.time.Instant) {
                            cell.setCellValue(val.toString());
                        } else {
                            cell.setCellValue(val.toString());
                        }
                    }
                }
            }

            // Auto-size columns (with minimum width for Chinese characters)
            for (int i = 0; i < fields.size(); i++) {
                sheet.autoSizeColumn(i);
                // Ensure minimum width for Chinese content
                int currentWidth = sheet.getColumnWidth(i);
                if (currentWidth < 3000) {
                    sheet.setColumnWidth(i, 3000);
                }
            }

            // Write to file
            try (java.io.OutputStream os = Files.newOutputStream(tempFile)) {
                workbook.write(os);
            }
        }
        return tempFile;
    }

    Path exportAsCsv(List<Map<String, Object>> data, List<String> fields,
                             Map<String, String> fieldLabelMap, String fileName, Boolean includeHeader)
            throws IOException {
        Path tempFile = Files.createTempFile(fileName, ".csv");
        try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
            // Write header with display labels
            if (!Boolean.FALSE.equals(includeHeader)) {
                List<String> headerLabels = fields.stream()
                        .map(f -> CsvSafetyUtils.escapeCsvCell(fieldLabelMap != null
                                ? fieldLabelMap.getOrDefault(f, f) : f))
                        .collect(Collectors.toList());
                writer.write(String.join(",", headerLabels));
                writer.newLine();
            }
            // Write data — escape every cell (formula-injection neutralization + RFC-4180)
            for (Map<String, Object> row : data) {
                List<String> values = fields.stream()
                        .map(field -> CsvSafetyUtils.escapeCsvCell(row.get(field)))
                        .collect(Collectors.toList());
                writer.write(String.join(",", values));
                writer.newLine();
            }
        }
        return tempFile;
    }

    Path exportAsJson(List<Map<String, Object>> data, List<String> fields,
                              Map<String, String> fieldLabelMap, String fileName)
            throws IOException {
        Path tempFile = Files.createTempFile(fileName, ".json");
        // Filter to only include specified fields, using display labels as keys
        List<Map<String, Object>> filtered = data.stream()
                .map(row -> {
                    Map<String, Object> filteredRow = new LinkedHashMap<>();
                    for (String field : fields) {
                        String key = (fieldLabelMap != null)
                                ? fieldLabelMap.getOrDefault(field, field) : field;
                        filteredRow.put(key, row.get(field));
                    }
                    return filteredRow;
                })
                .collect(Collectors.toList());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), filtered);
        return tempFile;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> parseJsonImport(Path filePath) throws IOException {
        Object parsed = objectMapper.readValue(filePath.toFile(), Object.class);
        if (parsed instanceof List) {
            return (List<Map<String, Object>>) parsed;
        }
        throw new MetaServiceException("JSON import file must contain an array of objects");
    }

    List<Map<String, Object>> parseCsvImport(Path filePath, Boolean skipFirstRow) throws IOException {
        List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return Collections.emptyList();
        }

        // First line is header
        String[] headers = lines.get(0).split(",", -1);
        for (int i = 0; i < headers.length; i++) {
            headers[i] = headers[i].trim().replace("\"", "");
        }

        int startLine = Boolean.FALSE.equals(skipFirstRow) ? 0 : 1;
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = startLine; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;

            String[] values = parseCsvLine(line);
            Map<String, Object> record = new LinkedHashMap<>();
            for (int j = 0; j < headers.length && j < values.length; j++) {
                String val = values[j].trim();
                record.put(headers[j], val.isEmpty() ? null : val);
            }
            records.add(record);
        }
        return records;
    }

    String[] parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                values.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values.toArray(new String[0]);
    }

    Map<String, Object> applyFieldMapping(Map<String, Object> row, Map<String, String> fieldMapping) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String targetField = fieldMapping.getOrDefault(entry.getKey(), entry.getKey());
            mapped.put(targetField, entry.getValue());
        }
        return mapped;
    }

}
