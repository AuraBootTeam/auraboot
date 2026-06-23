package com.auraboot.framework.user.service;

import com.auraboot.framework.user.dto.EmployeeAccountRow;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmployeeAccountWorkbookParserTest {

    private final EmployeeAccountWorkbookParser parser = new EmployeeAccountWorkbookParser();

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
}
