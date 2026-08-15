package com.auraboot.framework.user.service;

import com.auraboot.framework.user.dto.EmployeeAccountRow;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmployeeAccountWorkbookParserTest {
    private static final String SAX_FACTORY_PROPERTY = "javax.xml.parsers.SAXParserFactory";

    private final EmployeeAccountWorkbookParser parser = new EmployeeAccountWorkbookParser();

    @Test
    void writeTemplate_createsOpenableSixColumnWorkbook() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        parser.writeTemplate(out);

        byte[] bytes = out.toByteArray();
        assertThat(bytes).hasSizeGreaterThan(1000);
        assertThat(bytes[0]).isEqualTo((byte) 'P');
        assertThat(bytes[1]).isEqualTo((byte) 'K');
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("账号导入");
            assertThat(sheet).isNotNull();
            Row header = sheet.getRow(1);
            assertThat(List.of(
                    header.getCell(0).getStringCellValue(),
                    header.getCell(1).getStringCellValue(),
                    header.getCell(2).getStringCellValue(),
                    header.getCell(3).getStringCellValue(),
                    header.getCell(4).getStringCellValue(),
                    header.getCell(5).getStringCellValue()))
                    .containsExactly("姓名*", "登录名", "手机号", "工号", "部门编码", "岗位编码");
        }
    }

    @Test
    void parse_readsCanonicalTemplateBelowInstructionRowAndSkipsExample() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("账号导入");
        sheet.createRow(0).createCell(0).setCellValue("填写须知");
        Row header = sheet.createRow(1);
        header.createCell(0).setCellValue("姓名*");
        header.createCell(1).setCellValue("登录名");
        header.createCell(2).setCellValue("手机号");
        header.createCell(3).setCellValue("工号");
        header.createCell(4).setCellValue("部门编码");
        header.createCell(5).setCellValue("岗位编码");
        Row example = sheet.createRow(2);
        example.createCell(0).setCellValue("张三（示例，请删除此行）");
        Row row = sheet.createRow(3);
        row.createCell(0).setCellValue("王佳霞");
        row.createCell(1).setCellValue("wjx");
        row.createCell(2).setCellValue("13800000000");
        row.createCell(3).setCellValue("EMP001");
        row.createCell(4).setCellValue("SALES");
        row.createCell(5).setCellValue("SALES_REP");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();

        List<EmployeeAccountRow> rows = parser.parse(new ByteArrayInputStream(out.toByteArray()));

        assertThat(rows).hasSize(1);
        EmployeeAccountRow parsed = rows.get(0);
        assertThat(parsed.getName()).isEqualTo("王佳霞");
        assertThat(parsed.getUserName()).isEqualTo("wjx");
        assertThat(parsed.getMobile()).isEqualTo("13800000000");
        assertThat(parsed.getEmployeeCode()).isEqualTo("EMP001");
        assertThat(parsed.getDepartmentCode()).isEqualTo("SALES");
        assertThat(parsed.getPositionCode()).isEqualTo("SALES_REP");
        assertThat(parsed.getSourceRowNumber()).isEqualTo(4);
    }

    @Test
    void parse_readsNameTypeMobileAndEmailFromFirstSheet() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("在职人员信息");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("序号");
        header.createCell(1).setCellValue("姓名");
        header.createCell(2).setCellValue("类型");
        header.createCell(3).setCellValue("手机");
        header.createCell(4).setCellValue("邮箱");
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue(1);
        row.createCell(1).setCellValue("吴书生");
        row.createCell(2).setCellValue("管理员");
        row.createCell(3).setCellValue(18680666942D);
        row.createCell(4).setCellValue("admin@example.com");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();

        List<EmployeeAccountRow> rows = parser.parse(new ByteArrayInputStream(out.toByteArray()));

        assertThat(rows).hasSize(1);
        EmployeeAccountRow parsed = rows.get(0);
        assertThat(parsed.getName()).isEqualTo("吴书生");
        assertThat(parsed.getType()).isEqualTo("管理员");
        assertThat(parsed.getMobile()).isEqualTo("18680666942");
        assertThat(parsed.getEmail()).isEqualTo("admin@example.com");
    }

    @Test
    void parse_readsRolesColumnSplittingOnCommaAndSemicolon() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("在职人员信息");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("姓名");
        header.createCell(1).setCellValue("角色");
        Row withRoles = sheet.createRow(1);
        withRoles.createCell(0).setCellValue("袁称磊");
        withRoles.createCell(1).setCellValue("qo_sales，crm_account_common; sys_member");
        Row withoutRoles = sheet.createRow(2);
        withoutRoles.createCell(0).setCellValue("访客小陈");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();

        List<EmployeeAccountRow> rows = parser.parse(new ByteArrayInputStream(out.toByteArray()));

        assertThat(rows).hasSize(2);
        // roles split on comma / Chinese comma / semicolon, trimmed
        assertThat(rows.get(0).getName()).isEqualTo("袁称磊");
        assertThat(rows.get(0).getRoles()).containsExactly("qo_sales", "crm_account_common", "sys_member");
        // no type column at all, and an empty roles cell yields no roles (bare account)
        assertThat(rows.get(1).getName()).isEqualTo("访客小陈");
        assertThat(rows.get(1).getType()).isNull();
        assertThat(rows.get(1).getRoles()).isNull();
    }

    @Test
    void parse_skipsRowsWithoutNameAndType() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Sheet1");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("姓名");
        header.createCell(1).setCellValue("类型");
        sheet.createRow(1);
        Row row = sheet.createRow(2);
        row.createCell(0).setCellValue("袁称磊");
        row.createCell(1).setCellValue("销售");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();

        List<EmployeeAccountRow> rows = parser.parse(new ByteArrayInputStream(out.toByteArray()));

        assertThat(rows).extracting(EmployeeAccountRow::getName).containsExactly("袁称磊");
    }

    @Test
    void parse_doesNotUseGlobalPoiSaxReader() throws Exception {
        XSSFWorkbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("在职人员信息");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("姓名");
        header.createCell(1).setCellValue("类型");
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue("验证工程A");
        row.createCell(1).setCellValue("工程");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();

        String previous = System.getProperty(SAX_FACTORY_PROPERTY);
        System.setProperty(SAX_FACTORY_PROPERTY, "com.example.DoesNotExistSaxParserFactory");
        try {
            List<EmployeeAccountRow> rows = parser.parse(new ByteArrayInputStream(out.toByteArray()));

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).getName()).isEqualTo("验证工程A");
        } finally {
            if (previous == null) {
                System.clearProperty(SAX_FACTORY_PROPERTY);
            } else {
                System.setProperty(SAX_FACTORY_PROPERTY, previous);
            }
        }
    }

    @Test
    void parse_rejectsExternalEntityDeclarations() throws Exception {
        String maliciousSheet = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE worksheet [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>&xxe;</t></is></c></row></sheetData>
                </worksheet>
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zip.write(maliciousSheet.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(out.toByteArray())))
                .hasMessageContaining("Failed to parse employee account workbook")
                .hasMessageNotContaining("root:");
    }
}
