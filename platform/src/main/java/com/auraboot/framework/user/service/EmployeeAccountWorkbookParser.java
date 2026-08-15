package com.auraboot.framework.user.service;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dto.EmployeeAccountRow;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
    private static final List<String> TEMPLATE_HEADERS = List.of(
            "姓名*", "登录名", "手机号", "工号", "部门编码", "岗位编码");

    public List<EmployeeAccountRow> parse(InputStream inputStream) {
        try {
            List<List<String>> sheet = readFirstXlsxSheet(inputStream);
            if (sheet.isEmpty()) {
                throw new BusinessException("Workbook has no sheets");
            }
            int headerRowIndex = findHeaderRow(sheet);
            Map<String, Integer> columns = readColumns(sheet.get(headerRowIndex));
            int nameColumn = requiredColumn(columns, "name", "姓名/name");
            Integer userNameColumn = columns.get("userName");
            Integer typeColumn = columns.get("type");
            Integer rolesColumn = columns.get("roles");
            Integer mobileColumn = columns.get("mobile");
            Integer emailColumn = columns.get("email");
            Integer employeeCodeColumn = columns.get("employeeCode");
            Integer departmentCodeColumn = columns.get("departmentCode");
            Integer positionCodeColumn = columns.get("positionCode");

            List<EmployeeAccountRow> rows = new ArrayList<>();
            for (int i = headerRowIndex + 1; i < sheet.size(); i++) {
                List<String> row = sheet.get(i);
                String name = readCell(row, nameColumn);
                String userName = userNameColumn == null ? null : readCell(row, userNameColumn);
                String type = typeColumn == null ? null : readCell(row, typeColumn);
                String mobile = mobileColumn == null ? null : readCell(row, mobileColumn);
                String email = emailColumn == null ? null : readCell(row, emailColumn);
                String employeeCode = employeeCodeColumn == null ? null : readCell(row, employeeCodeColumn);
                String departmentCode = departmentCodeColumn == null ? null : readCell(row, departmentCodeColumn);
                String positionCode = positionCodeColumn == null ? null : readCell(row, positionCodeColumn);
                if (allBlank(name, userName, type, mobile, email, employeeCode, departmentCode, positionCode)) {
                    continue;
                }
                if (isTemplateExampleRow(name)) {
                    continue;
                }
                EmployeeAccountRow accountRow = new EmployeeAccountRow();
                accountRow.setName(name);
                accountRow.setUserName(userName);
                accountRow.setType(type);
                accountRow.setRoles(rolesColumn == null ? null : parseRoles(readCell(row, rolesColumn)));
                accountRow.setMobile(mobile);
                accountRow.setEmail(email);
                accountRow.setEmployeeCode(employeeCode);
                accountRow.setDepartmentCode(departmentCode);
                accountRow.setPositionCode(positionCode);
                accountRow.setSourceRowNumber(i + 1);
                rows.add(accountRow);
            }
            return rows;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("Failed to parse employee account workbook: " + e.getMessage());
        }
    }

    /**
     * Write the canonical six-column account import template.
     */
    public void writeTemplate(OutputStream outputStream) {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet("账号导入");
            sheet.setDisplayGridlines(false);
            sheet.createFreezePane(0, 2);

            CellStyle instructionStyle = workbook.createCellStyle();
            instructionStyle.setWrapText(true);
            instructionStyle.setVerticalAlignment(VerticalAlignment.TOP);
            instructionStyle.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
            instructionStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            instructionStyle.setBorderBottom(BorderStyle.THIN);
            instructionStyle.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());

            org.apache.poi.ss.usermodel.Row instructions = sheet.createRow(0);
            instructions.setHeightInPoints(92);
            instructions.createCell(0).setCellValue(String.join("\n",
                    "填写须知：",
                    "1. 请勿修改表格结构；红色字段必填，黑色字段选填。",
                    "2. 登录名为空时默认使用姓名；登录名重复会在预检时报错。",
                    "3. 工号、部门编码、岗位编码按租户内唯一编码精确匹配。",
                    "4. 组织字段全部为空时只创建账号；填写组织关系时必须填写工号和部门编码。",
                    "5. 邮箱和权限不在模板中；密码由系统生成，权限由管理员手工分配。",
                    "6. 示例行不会导入，正式填写时建议删除。"));
            instructions.getCell(0).setCellStyle(instructionStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, TEMPLATE_HEADERS.size() - 1));

            CellStyle optionalHeaderStyle = headerStyle(workbook, IndexedColors.GREY_80_PERCENT);
            CellStyle requiredHeaderStyle = headerStyle(workbook, IndexedColors.DARK_RED);
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(1);
            for (int i = 0; i < TEMPLATE_HEADERS.size(); i++) {
                header.createCell(i).setCellValue(TEMPLATE_HEADERS.get(i));
                header.getCell(i).setCellStyle(i == 0 ? requiredHeaderStyle : optionalHeaderStyle);
            }

            CellStyle exampleStyle = workbook.createCellStyle();
            Font exampleFont = workbook.createFont();
            exampleFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            exampleFont.setItalic(true);
            exampleStyle.setFont(exampleFont);
            exampleStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            org.apache.poi.ss.usermodel.Row example = sheet.createRow(2);
            List<String> exampleValues = List.of(
                    "张三（示例，请删除此行）", "张三", "13800000000", "EMP001", "SALES", "SALES_REP");
            for (int i = 0; i < exampleValues.size(); i++) {
                example.createCell(i).setCellValue(exampleValues.get(i));
                example.getCell(i).setCellStyle(exampleStyle);
            }

            int[] widths = {24, 20, 20, 18, 18, 18};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
                sheet.setDefaultColumnStyle(i, textStyle(workbook));
            }
            sheet.setAutoFilter(new CellRangeAddress(1, 2, 0, TEMPLATE_HEADERS.size() - 1));
            workbook.write(outputStream);
        } catch (Exception e) {
            throw new BusinessException("Failed to generate employee account workbook: " + e.getMessage());
        }
    }

    private CellStyle headerStyle(XSSFWorkbook workbook, IndexedColors color) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(color.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        return style;
    }

    private CellStyle textStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("@"));
        return style;
    }

    private int findHeaderRow(List<List<String>> sheet) {
        int limit = Math.min(sheet.size(), 20);
        for (int i = 0; i < limit; i++) {
            Map<String, Integer> columns = readColumns(sheet.get(i));
            if (columns.containsKey("name")) {
                return i;
            }
        }
        throw new BusinessException("Missing required column: 姓名/name");
    }

    private Map<String, Integer> readColumns(List<String> header) {
        if (header == null) {
            throw new BusinessException("Workbook header row is missing");
        }
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String key = normalizeHeader(header.get(i));
            if (key != null) {
                if (columns.putIfAbsent(key, i) != null) {
                    throw new BusinessException("Duplicate workbook column: " + header.get(i));
                }
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
            case "姓名", "姓名*", "name", "employee name" -> "name";
            case "登录名", "用户名", "username", "user name", "login name" -> "userName";
            case "类型", "type", "employee type" -> "type";
            case "角色", "roles", "role", "role codes" -> "roles";
            case "手机", "手机号", "mobile", "phone", "phone number" -> "mobile";
            case "邮箱", "email", "email address" -> "email";
            case "工号", "员工编码", "employee code" -> "employeeCode";
            case "部门编码", "department code" -> "departmentCode";
            case "岗位编码", "职位编码", "position code" -> "positionCode";
            default -> null;
        };
    }

    private boolean allBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return false;
            }
        }
        return true;
    }

    private boolean isTemplateExampleRow(String name) {
        return name != null && name.contains("示例") && name.contains("删除");
    }

    // A roles cell holds zero or more role codes separated by comma, Chinese
    // comma, or semicolon. Blank cells and blank entries are dropped, so an
    // empty cell yields no roles (a bare account).
    private List<String> parseRoles(String value) {
        if (isBlank(value)) {
            return null;
        }
        List<String> roles = new ArrayList<>();
        for (String part : value.split("[,，;；]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                roles.add(trimmed);
            }
        }
        return roles.isEmpty() ? null : roles;
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

    // Decompression-bomb guards: an xlsx is a zip; a small upload can inflate to GBs.
    private static final long MAX_ZIP_ENTRY_SIZE = 10L * 1024 * 1024;    // 10 MB per entry
    private static final int MAX_ZIP_ENTRIES = 1000;
    private static final long MAX_ZIP_TOTAL_SIZE = 100L * 1024 * 1024;   // 100 MB total uncompressed

    Map<String, byte[]> readZipEntries(InputStream inputStream) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            int entryCount = 0;
            long totalSize = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (++entryCount > MAX_ZIP_ENTRIES) {
                    throw new IllegalArgumentException(
                            "Workbook exceeds maximum entry count: " + MAX_ZIP_ENTRIES);
                }
                // readNBytes(MAX+1) bounds heap use; length > MAX means an oversized entry.
                byte[] content = zip.readNBytes((int) MAX_ZIP_ENTRY_SIZE + 1);
                if (content.length > MAX_ZIP_ENTRY_SIZE) {
                    throw new IllegalArgumentException("Workbook entry exceeds maximum size ("
                            + (MAX_ZIP_ENTRY_SIZE / (1024 * 1024)) + " MB): " + entry.getName());
                }
                totalSize += content.length;
                if (totalSize > MAX_ZIP_TOTAL_SIZE) {
                    throw new IllegalArgumentException("Workbook uncompressed size exceeds maximum ("
                            + (MAX_ZIP_TOTAL_SIZE / (1024 * 1024)) + " MB)");
                }
                entries.put(entry.getName(), content);
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
