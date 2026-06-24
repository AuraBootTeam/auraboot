package com.auraboot.framework.user.service;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dto.EmployeeAccountRow;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class EmployeeAccountWorkbookParser {
    private static final String SHEET_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    public List<EmployeeAccountRow> parse(InputStream inputStream) {
        try {
            List<List<String>> sheet = readFirstXlsxSheet(inputStream);
            if (sheet.isEmpty()) {
                throw new BusinessException("Workbook has no sheets");
            }
            Map<String, Integer> columns = readColumns(sheet.get(0));
            int nameColumn = requiredColumn(columns, "name", "姓名/name");
            int typeColumn = requiredColumn(columns, "type", "类型/type");
            Integer mobileColumn = columns.get("mobile");
            Integer emailColumn = columns.get("email");

            List<EmployeeAccountRow> rows = new ArrayList<>();
            for (int i = 1; i < sheet.size(); i++) {
                List<String> row = sheet.get(i);
                String name = readCell(row, nameColumn);
                String type = readCell(row, typeColumn);
                if (isBlank(name) && isBlank(type)) {
                    continue;
                }
                EmployeeAccountRow accountRow = new EmployeeAccountRow();
                accountRow.setName(name);
                accountRow.setType(type);
                accountRow.setMobile(mobileColumn == null ? null : readCell(row, mobileColumn));
                accountRow.setEmail(emailColumn == null ? null : readCell(row, emailColumn));
                rows.add(accountRow);
            }
            return rows;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Failed to parse employee account workbook: " + e.getMessage());
        }
    }

    private Map<String, Integer> readColumns(List<String> header) {
        if (header == null) {
            throw new BusinessException("Workbook header row is missing");
        }
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String key = normalizeHeader(header.get(i));
            if (key != null) {
                columns.put(key, i);
            }
        }
        return columns;
    }

    private int requiredColumn(Map<String, Integer> columns, String key, String label) {
        Integer column = columns.get(key);
        if (column == null) {
            throw new BusinessException("Missing required column: " + label);
        }
        return column;
    }

    private String normalizeHeader(String value) {
        if (isBlank(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "姓名", "name", "employee name" -> "name";
            case "类型", "type", "employee type" -> "type";
            case "手机", "手机号", "mobile", "phone", "phone number" -> "mobile";
            case "邮箱", "email", "email address" -> "email";
            default -> null;
        };
    }

    private String readCell(List<String> row, int column) {
        if (column < 0 || column >= row.size()) {
            return null;
        }
        String value = row.get(column);
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private List<List<String>> readFirstXlsxSheet(InputStream inputStream) throws Exception {
        Map<String, byte[]> entries = readZipEntries(inputStream);
        byte[] sheet = entries.get("xl/worksheets/sheet1.xml");
        if (sheet == null) {
            sheet = entries.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith("xl/worksheets/sheet") && entry.getKey().endsWith(".xml"))
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow(() -> new BusinessException("Workbook has no sheets"));
        }
        List<String> sharedStrings = sharedStrings(entries.get("xl/sharedStrings.xml"));
        return readSheet(sheet, sharedStrings);
    }

    private Map<String, byte[]> readZipEntries(InputStream inputStream) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
        }
        return entries;
    }

    private List<String> sharedStrings(byte[] bytes) throws Exception {
        List<String> out = new ArrayList<>();
        if (bytes == null) {
            return out;
        }
        Document doc = parseXml(bytes);
        NodeList items = doc.getElementsByTagNameNS(SHEET_NS, "si");
        for (int i = 0; i < items.getLength(); i++) {
            out.add(textNodes((Element) items.item(i)));
        }
        return out;
    }

    private List<List<String>> readSheet(byte[] bytes, List<String> sharedStrings) throws Exception {
        Document doc = parseXml(bytes);
        NodeList rows = doc.getElementsByTagNameNS(SHEET_NS, "row");
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < rows.getLength(); i++) {
            Element row = (Element) rows.item(i);
            List<String> values = new ArrayList<>();
            NodeList cells = row.getElementsByTagNameNS(SHEET_NS, "c");
            for (int j = 0; j < cells.getLength(); j++) {
                Element cell = (Element) cells.item(j);
                int column = columnIndex(cell.getAttribute("r"), values.size());
                while (values.size() <= column) {
                    values.add("");
                }
                values.set(column, cellValue(cell, sharedStrings));
            }
            out.add(values);
        }
        return out;
    }

    private String cellValue(Element cell, List<String> sharedStrings) {
        String type = cell.getAttribute("t");
        if ("inlineStr".equals(type)) {
            return textNodes(cell).trim();
        }
        String raw = firstChildText(cell, "v").trim();
        if ("s".equals(type) && !raw.isBlank()) {
            int index = Integer.parseInt(raw);
            return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index).trim() : "";
        }
        if ("str".equals(type)) {
            return raw;
        }
        return normalizeNumeric(raw);
    }

    private String normalizeNumeric(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        try {
            return new BigDecimal(raw).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            return raw;
        }
    }

    private String firstChildText(Element element, String localName) {
        NodeList nodes = element.getElementsByTagNameNS(SHEET_NS, localName);
        if (nodes.getLength() == 0) {
            return "";
        }
        String text = nodes.item(0).getTextContent();
        return text == null ? "" : text;
    }

    private String textNodes(Element element) {
        NodeList nodes = element.getElementsByTagNameNS(SHEET_NS, "t");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getTextContent() != null) {
                out.append(node.getTextContent());
            }
        }
        return out.toString();
    }

    private int columnIndex(String cellRef, int fallback) {
        if (cellRef == null || cellRef.isBlank()) {
            return fallback;
        }
        int result = 0;
        int letters = 0;
        for (int i = 0; i < cellRef.length(); i++) {
            char ch = Character.toUpperCase(cellRef.charAt(i));
            if (ch < 'A' || ch > 'Z') {
                break;
            }
            result = result * 26 + (ch - 'A' + 1);
            letters++;
        }
        return letters == 0 ? fallback : result - 1;
    }

    private Document parseXml(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(
                "com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl",
                EmployeeAccountWorkbookParser.class.getClassLoader());
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl");
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities");
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private void trySetFeature(DocumentBuilderFactory factory, String feature) {
        try {
            boolean value = !feature.toLowerCase(Locale.ROOT).contains("external-");
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
            // XML parser hardening is best-effort because JDK vendors expose different feature sets.
        }
    }
}
