package com.auraboot.module.meta.excel;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.module.meta.excel.mapper.ImportJobMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExcelImportService.
 */
@ExtendWith(MockitoExtension.class)
class ExcelImportServiceTest {

    @Mock
    private DynamicDataService dynamicDataService;

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private ImportJobMapper importJobMapper;

    private ExcelImportService importService;

    @BeforeEach
    void setUp() {
        importService = new ExcelImportService(dynamicDataService, metaModelService, importJobMapper);
    }

    /**
     * Create an in-memory .xlsx workbook and return it as InputStream.
     */
    private ByteArrayInputStream createExcel(String[] headers, String[][] data) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");

            // Header row
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }

            // Data rows
            for (int r = 0; r < data.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < data[r].length; c++) {
                    row.createCell(c).setCellValue(data[r][c]);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    @Test
    void testParseExcelHeaders() throws IOException {
        String[] headers = {"name", "code", "price"};
        String[][] data = {
                {"Widget A", "w001", "10.5"},
                {"Widget B", "w002", "20.0"}
        };

        ByteArrayInputStream stream = createExcel(headers, data);
        List<Map<String, String>> rows = importService.parseExcel(stream, "yyyy-MM-dd");

        assertEquals(2, rows.size());
        assertEquals("Widget A", rows.get(0).get("name"));
        assertEquals("w001", rows.get(0).get("code"));
        assertEquals("10.5", rows.get(0).get("price"));
        assertEquals("Widget B", rows.get(1).get("name"));
    }

    @Test
    void testValidateEmptyFile() throws IOException {
        // Workbook with header only, no data
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("name");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            ByteArrayInputStream stream = new ByteArrayInputStream(out.toByteArray());

            ImportOptions options = new ImportOptions();
            ExcelImportResult result = importService.importExcel("test_model", stream, options);

            assertEquals(0, result.getTotalRows());
            assertEquals(0, result.getSuccessCount());
            assertEquals(0, result.getErrorCount());
            assertFalse(result.isHasErrors());
        }
    }

    @Test
    void testDryRunNoInsert() throws IOException {
        String[] headers = {"name", "code"};
        String[][] data = {
                {"Item A", "a001"},
                {"Item B", "b001"}
        };

        ByteArrayInputStream stream = createExcel(headers, data);
        when(metaModelService.getModelFields("test_model")).thenReturn(List.of());

        ImportOptions options = new ImportOptions();
        options.setDryRun(true);

        ExcelImportResult result = importService.importExcel("test_model", stream, options);

        assertEquals(2, result.getTotalRows());
        assertEquals(0, result.getSuccessCount());
        // No calls to dynamicDataService
        verify(dynamicDataService, never()).create(anyString(), anyMap());
    }

    @Test
    void testSkipErrorsContinues() throws IOException {
        String[] headers = {"name", "code"};
        String[][] data = {
                {"Item 1", "c001"},
                {"Item 2", "c002"},
                {"Item 3", "c003"}
        };

        ByteArrayInputStream stream = createExcel(headers, data);
        when(metaModelService.getModelFields("test_model")).thenReturn(List.of());

        // batchCreate fails → falls back to per-row create
        when(dynamicDataService.batchCreate(eq("test_model"), anyList()))
                .thenThrow(new RuntimeException("Batch error"));

        // Row 2 (index 1) throws an exception in per-row fallback
        when(dynamicDataService.create(eq("test_model"), anyMap()))
                .thenReturn(Map.of("id", "1"))                          // row 1 succeeds
                .thenThrow(new RuntimeException("Duplicate code"))      // row 2 fails
                .thenReturn(Map.of("id", "3"));                         // row 3 succeeds

        ImportOptions options = new ImportOptions();
        options.setSkipErrors(true);

        ExcelImportResult result = importService.importExcel("test_model", stream, options);

        assertEquals(2, result.getSuccessCount());
        assertEquals(1, result.getErrorCount());
        assertTrue(result.isHasErrors());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).getMessage().contains("Duplicate code"));
    }

    @Test
    void testStopOnFirstErrorWhenSkipErrorsDisabled() throws IOException {
        String[] headers = {"name"};
        String[][] data = {
                {"A"},
                {"B"},
                {"C"}
        };

        ByteArrayInputStream stream = createExcel(headers, data);
        when(metaModelService.getModelFields("test_model")).thenReturn(List.of());

        // batchCreate fails → falls back to per-row create
        when(dynamicDataService.batchCreate(eq("test_model"), anyList()))
                .thenThrow(new RuntimeException("Batch error"));

        when(dynamicDataService.create(eq("test_model"), anyMap()))
                .thenReturn(Map.of("id", "1"))
                .thenThrow(new RuntimeException("Validation failed"));

        ImportOptions options = new ImportOptions();
        options.setSkipErrors(false);

        ExcelImportResult result = importService.importExcel("test_model", stream, options);

        // Should stop after row 2 fails
        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getErrorCount());
        // Row 3 should never be attempted
        verify(dynamicDataService, times(2)).create(anyString(), anyMap());
    }

    // ==================== resolveHeaderMapping tests ====================

    @Test
    void testResolveHeaderMapping_displayNames() {
        var fields = List.of(
                FieldDefinition.builder().code("pe_so_code").displayName("Order Code").build(),
                FieldDefinition.builder().code("pe_so_total").displayName("Total Amount").build()
        );
        var mapping = ExcelImportService.resolveHeaderMapping(
                List.of("Order Code", "Total Amount"), fields);
        assertEquals("pe_so_code", mapping.get("Order Code"));
        assertEquals("pe_so_total", mapping.get("Total Amount"));
    }

    @Test
    void testResolveHeaderMapping_fieldCodes() {
        var fields = List.of(
                FieldDefinition.builder().code("pe_so_code").displayName("Order Code").build()
        );
        var mapping = ExcelImportService.resolveHeaderMapping(List.of("pe_so_code"), fields);
        assertEquals("pe_so_code", mapping.get("pe_so_code"));
    }

    @Test
    void testResolveHeaderMapping_mixed() {
        var fields = List.of(
                FieldDefinition.builder().code("pe_so_code").displayName("Order Code").build(),
                FieldDefinition.builder().code("pe_so_total").displayName("Total").build()
        );
        var mapping = ExcelImportService.resolveHeaderMapping(
                List.of("Order Code", "pe_so_total"), fields);
        assertEquals("pe_so_code", mapping.get("Order Code"));
        assertEquals("pe_so_total", mapping.get("pe_so_total"));
    }

    @Test
    void testResolveHeaderMapping_requiredMarker() {
        var fields = List.of(
                FieldDefinition.builder().code("name").displayName("Name").build()
        );
        var mapping = ExcelImportService.resolveHeaderMapping(List.of("* Name"), fields);
        assertEquals("name", mapping.get("* Name"));
    }

    @Test
    void testResolveHeaderMapping_unmatchedPassthrough() {
        var fields = List.of(
                FieldDefinition.builder().code("pe_so_code").displayName("Order Code").build()
        );
        var mapping = ExcelImportService.resolveHeaderMapping(
                List.of("Unknown Column"), fields);
        assertEquals("Unknown Column", mapping.get("Unknown Column"));
    }

    @Test
    void testResolveHeaderMapping_nullAndBlankHeaders() {
        var fields = List.of(
                FieldDefinition.builder().code("name").displayName("Name").build()
        );
        var mapping = ExcelImportService.resolveHeaderMapping(
                List.of("Name", "", "  "), fields);
        assertEquals(1, mapping.size());
        assertEquals("name", mapping.get("Name"));
    }

    @Test
    void testResolveHeaderMapping_requiredMarkerOnFieldCode() {
        var fields = List.of(
                FieldDefinition.builder().code("pe_so_code").displayName("Order Code").build()
        );
        var mapping = ExcelImportService.resolveHeaderMapping(List.of("* pe_so_code"), fields);
        assertEquals("pe_so_code", mapping.get("* pe_so_code"));
    }

    // ==================== generateImportTemplate tests ====================

    @Test
    void testGenerateTemplate_basicFields() throws IOException {
        var fields = List.of(
                FieldDefinition.builder().code("pe_so_code").displayName("Order Code").required(true).build(),
                FieldDefinition.builder().code("pe_so_name").displayName("Name").required(false).build(),
                FieldDefinition.builder().code("pe_so_qty").displayName("Quantity").required(true).build()
        );
        when(metaModelService.getModelFields("test_model")).thenReturn(fields);

        Path template = importService.generateImportTemplate("test_model");
        assertNotNull(template);
        assertTrue(Files.exists(template));

        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(template))) {
            var sheet = wb.getSheetAt(0);
            var headerRow = sheet.getRow(0);
            assertEquals("* Order Code", headerRow.getCell(0).getStringCellValue());
            assertEquals("Name", headerRow.getCell(1).getStringCellValue());
            assertEquals("* Quantity", headerRow.getCell(2).getStringCellValue());
            assertEquals(1, sheet.getPhysicalNumberOfRows()); // header only, no data
        } finally {
            Files.deleteIfExists(template);
        }
    }

    @Test
    void testGenerateTemplate_excludesAutoFields() throws IOException {
        var fields = List.of(
                FieldDefinition.builder().code("id").displayName("ID").primaryKey(true).build(),
                FieldDefinition.builder().code("pid").displayName("pid").build(),
                FieldDefinition.builder().code("created_at").displayName("Created").build(),
                FieldDefinition.builder().code("tenant_id").displayName("Tenant").build(),
                FieldDefinition.builder().code("pe_so_name").displayName("Name").build()
        );
        when(metaModelService.getModelFields("test_model")).thenReturn(fields);

        Path template = importService.generateImportTemplate("test_model");

        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(template))) {
            var headerRow = wb.getSheetAt(0).getRow(0);
            // Only pe_so_name should be in template
            assertEquals(1, headerRow.getLastCellNum());
            assertEquals("Name", headerRow.getCell(0).getStringCellValue());
        } finally {
            Files.deleteIfExists(template);
        }
    }

    @Test
    void testGenerateTemplate_excludesComputedReadonly() throws IOException {
        var fields = List.of(
                FieldDefinition.builder().code("pe_total").displayName("Total").virtualType("computed_readonly").build(),
                FieldDefinition.builder().code("pe_name").displayName("Name").build()
        );
        when(metaModelService.getModelFields("test_model")).thenReturn(fields);

        Path template = importService.generateImportTemplate("test_model");

        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(template))) {
            var headerRow = wb.getSheetAt(0).getRow(0);
            assertEquals(1, headerRow.getLastCellNum());
            assertEquals("Name", headerRow.getCell(0).getStringCellValue());
        } finally {
            Files.deleteIfExists(template);
        }
    }

    @Test
    void testGenerateTemplate_fallsBackToCodeWhenNoDisplayName() throws IOException {
        var fields = List.of(
                FieldDefinition.builder().code("pe_so_code").build(),
                FieldDefinition.builder().code("pe_so_name").displayName("").build()
        );
        when(metaModelService.getModelFields("test_model")).thenReturn(fields);

        Path template = importService.generateImportTemplate("test_model");

        try (XSSFWorkbook wb = new XSSFWorkbook(Files.newInputStream(template))) {
            var headerRow = wb.getSheetAt(0).getRow(0);
            assertEquals("pe_so_code", headerRow.getCell(0).getStringCellValue());
            assertEquals("pe_so_name", headerRow.getCell(1).getStringCellValue());
        } finally {
            Files.deleteIfExists(template);
        }
    }

    @Test
    void testImportExcel_withDisplayNameHeaders() throws IOException {
        // Create Excel with displayName headers instead of fieldCode headers
        String[] headers = {"Order Code", "Total Amount"};
        String[][] data = {
                {"SO-001", "1000.00"}
        };
        ByteArrayInputStream stream = createExcel(headers, data);

        var fields = List.of(
                FieldDefinition.builder().code("pe_so_code").displayName("Order Code").build(),
                FieldDefinition.builder().code("pe_so_total").displayName("Total Amount").build()
        );
        when(metaModelService.getModelFields("test_model")).thenReturn(fields);

        // batchCreate succeeds (batch path)
        when(dynamicDataService.batchCreate(eq("test_model"), anyList()))
                .thenReturn(new com.auraboot.framework.meta.dto.DynamicBatchResponse());

        ImportOptions options = new ImportOptions();
        ExcelImportResult result = importService.importExcel("test_model", stream, options);

        assertEquals(1, result.getSuccessCount());
        // Verify batchCreate was called with mapped field codes
        verify(dynamicDataService).batchCreate(eq("test_model"), argThat(list ->
                list.size() == 1
                        && list.get(0).containsKey("pe_so_code")
                        && list.get(0).containsKey("pe_so_total")
                        && "SO-001".equals(list.get(0).get("pe_so_code"))
                        && "1000.00".equals(list.get(0).get("pe_so_total"))
        ));
    }

    @Test
    void testBatchInsert_happyPath() throws IOException {
        String[] headers = {"name"};
        String[][] data = {{"A"}, {"B"}, {"C"}};

        ByteArrayInputStream stream = createExcel(headers, data);
        when(metaModelService.getModelFields("test_model")).thenReturn(List.of());

        // batchCreate succeeds
        when(dynamicDataService.batchCreate(eq("test_model"), anyList()))
                .thenReturn(new com.auraboot.framework.meta.dto.DynamicBatchResponse());

        ExcelImportResult result = importService.importExcel("test_model", stream, new ImportOptions());

        assertEquals(3, result.getSuccessCount());
        assertEquals(0, result.getErrorCount());
        // create should NOT be called when batch succeeds
        verify(dynamicDataService, never()).create(anyString(), anyMap());
    }

    // ==================== UPSERT mode tests ====================

    @Test
    void testUpsert_createsWhenNotFound() throws IOException {
        String[] headers = {"code", "name"};
        String[][] data = {{"NEW-001", "New Item"}};
        ByteArrayInputStream stream = createExcel(headers, data);

        when(metaModelService.getModelFields("test_model")).thenReturn(List.of());
        // list() returns empty — no existing record
        when(dynamicDataService.list(eq("test_model"), any()))
                .thenReturn(new com.auraboot.framework.meta.dto.PaginationResult<>(List.of(), 0L, 1, 1));
        when(dynamicDataService.create(eq("test_model"), anyMap()))
                .thenReturn(Map.of("pid", "1"));

        ImportOptions options = new ImportOptions();
        options.setUpsertKey("code");

        ExcelImportResult result = importService.importExcel("test_model", stream, options);

        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getCreatedCount());
        assertEquals(0, result.getUpdatedCount());
        verify(dynamicDataService).create(eq("test_model"), anyMap());
        verify(dynamicDataService, never()).update(anyString(), anyString(), anyMap());
    }

    @Test
    void testUpsert_updatesWhenFound() throws IOException {
        String[] headers = {"code", "name"};
        String[][] data = {{"EXIST-001", "Updated Name"}};
        ByteArrayInputStream stream = createExcel(headers, data);

        when(metaModelService.getModelFields("test_model")).thenReturn(List.of());
        // list() returns existing record
        when(dynamicDataService.list(eq("test_model"), any()))
                .thenReturn(new com.auraboot.framework.meta.dto.PaginationResult<>(
                        List.of(Map.of("pid", "existing-pid-123")), 1L, 1, 1));
        when(dynamicDataService.update(eq("test_model"), eq("existing-pid-123"), anyMap()))
                .thenReturn(Map.of("pid", "existing-pid-123"));

        ImportOptions options = new ImportOptions();
        options.setUpsertKey("code");

        ExcelImportResult result = importService.importExcel("test_model", stream, options);

        assertEquals(1, result.getSuccessCount());
        assertEquals(0, result.getCreatedCount());
        assertEquals(1, result.getUpdatedCount());
        verify(dynamicDataService).update(eq("test_model"), eq("existing-pid-123"), anyMap());
        verify(dynamicDataService, never()).create(anyString(), anyMap());
    }
}
