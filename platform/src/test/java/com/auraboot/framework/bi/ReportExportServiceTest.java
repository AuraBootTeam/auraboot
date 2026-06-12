package com.auraboot.framework.bi;

import com.auraboot.framework.bi.dto.ReportExportFile;
import com.auraboot.framework.bi.dto.ReportExportRequest;
import com.auraboot.framework.bi.service.impl.ReportExportServiceImpl;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportExportServiceTest {

    @Mock
    private PageSchemaMapper pageSchemaMapper;

    private ReportExportServiceImpl reportExportService;

    @BeforeEach
    void setUp() {
        reportExportService = new ReportExportServiceImpl(pageSchemaMapper, new ObjectMapper());
    }

    @Test
    void exportExcel_withStaticTableData_rendersWorkbookArtifact() throws Exception {
        PageSchema page = new PageSchema();
        ExtensionBean extension = new ExtensionBean();
        extension.setDynamicProperty("reportDsl", reportDsl());
        page.setExtension(extension);

        when(pageSchemaMapper.selectByPid("rpt-001")).thenReturn(page);

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-001");

        ReportExportFile file = reportExportService.exportExcel(request);

        assertThat(file.getFilename()).isEqualTo("Operations Export.xlsx");
        assertThat(file.getContentType()).contains("spreadsheetml.sheet");
        assertThat(file.getBytes()).startsWith((byte) 'P', (byte) 'K');

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(file.getBytes()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            assertThat(workbook.getSheetName(0)).isEqualTo("Orders Export");
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Orders Export");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Region");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Cases");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("North");
            assertThat(sheet.getRow(2).getCell(1).getNumericCellValue()).isEqualTo(12.0);
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("South");
            assertThat(sheet.getRow(3).getCell(1).getNumericCellValue()).isEqualTo(9.0);
        }
    }

    @Test
    void exportExcel_withoutReportDsl_throwsValidationException() {
        PageSchema page = new PageSchema();
        page.setExtension(new ExtensionBean());
        when(pageSchemaMapper.selectByPid("rpt-missing")).thenReturn(page);

        ReportExportRequest request = new ReportExportRequest();
        request.setReportPid("rpt-missing");

        assertThatThrownBy(() -> reportExportService.exportExcel(request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Report DSL not found");
    }

    private Map<String, Object> reportDsl() {
        Map<String, Object> rowNorth = new LinkedHashMap<>();
        rowNorth.put("region", "North");
        rowNorth.put("cases", 12);
        Map<String, Object> rowSouth = new LinkedHashMap<>();
        rowSouth.put("region", "South");
        rowSouth.put("cases", 9);

        Map<String, Object> dataSource = new LinkedHashMap<>();
        dataSource.put("type", "static");
        dataSource.put("data", List.of(rowNorth, rowSouth));

        Map<String, Object> table = new LinkedHashMap<>();
        table.put("id", "table-orders");
        table.put("blockType", "table");
        table.put("title", "Orders Export");
        table.put("dataSource", "orders");
        table.put("showHeader", true);
        table.put("columns", List.of(
                Map.of("field", "region", "label", "Region"),
                Map.of("field", "cases", "label", "Cases")
        ));

        Map<String, Object> dsl = new LinkedHashMap<>();
        dsl.put("$schema", "auraboot://schemas/report/v1");
        dsl.put("version", "1.0.0");
        dsl.put("title", "Operations Export");
        dsl.put("dataSources", Map.of("orders", dataSource));
        dsl.put("body", List.of(table));
        return dsl;
    }
}
