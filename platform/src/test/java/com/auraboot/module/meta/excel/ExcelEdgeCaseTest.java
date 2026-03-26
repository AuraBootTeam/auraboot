package com.auraboot.module.meta.excel;

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

import com.auraboot.framework.meta.dto.DynamicBatchResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Edge case tests for ExcelImportService covering boundary conditions:
 * empty files, mismatched headers, and larger datasets.
 */
@ExtendWith(MockitoExtension.class)
class ExcelEdgeCaseTest {

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

    // ========== Test 1: Empty Excel file (header only, 0 data rows) ==========

    @Test
    void testEmptyExcelFile() throws IOException {
        // Workbook with header only — no data rows at all
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("name");
            headerRow.createCell(1).setCellValue("code");
            headerRow.createCell(2).setCellValue("price");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            ByteArrayInputStream stream = new ByteArrayInputStream(out.toByteArray());

            ImportOptions options = new ImportOptions();
            ExcelImportResult result = importService.importExcel("test_model", stream, options);

            assertEquals(0, result.getTotalRows(), "No data rows means 0 total");
            assertEquals(0, result.getSuccessCount());
            assertEquals(0, result.getErrorCount());
            assertFalse(result.isHasErrors());

            // No calls to dynamicDataService since there are no rows to insert
            verify(dynamicDataService, never()).create(anyString(), anyMap());
        }
    }

    // ========== Test 2: Mismatched headers (extra columns in data) ==========

    @Test
    void testMismatchedHeaders() throws IOException {
        // Headers have 2 columns, but data rows have 3 columns
        // The extra column in data should be silently ignored
        // because parseExcel only reads up to headers.size() columns
        String[] headers = {"name", "code"};
        String[][] data = {
                {"Item A", "a001", "extra-ignored-column"},
                {"Item B", "b001", "another-extra"}
        };

        ByteArrayInputStream stream = createExcel(headers, data);
        when(metaModelService.getModelFields("test_model")).thenReturn(List.of());

        when(dynamicDataService.batchCreate(eq("test_model"), anyList()))
                .thenReturn(new DynamicBatchResponse());

        ImportOptions options = new ImportOptions();
        ExcelImportResult result = importService.importExcel("test_model", stream, options);

        // Should process 2 rows without errors
        assertEquals(2, result.getTotalRows());
        assertEquals(2, result.getSuccessCount());
        assertEquals(0, result.getErrorCount());
        assertFalse(result.isHasErrors());
    }

    // ========== Test 3: Fewer data columns than headers ==========

    @Test
    void testFewerDataColumnsThanHeaders() throws IOException {
        // Headers have 3 columns, but data rows have only 1 column
        // Missing columns should be empty strings
        String[] headers = {"name", "code", "price"};
        String[][] data = {
                {"Item A"}  // only 1 of 3 columns filled
        };

        ByteArrayInputStream stream = createExcel(headers, data);
        when(metaModelService.getModelFields("test_model")).thenReturn(List.of());

        when(dynamicDataService.batchCreate(eq("test_model"), anyList()))
                .thenReturn(new DynamicBatchResponse());

        ImportOptions options = new ImportOptions();
        ExcelImportResult result = importService.importExcel("test_model", stream, options);

        assertEquals(1, result.getTotalRows());
        assertEquals(1, result.getSuccessCount());
    }

    // ========== Test 4: Large dataset (100 rows) all processed ==========

    @Test
    void testLargeDataset() throws IOException {
        int rowCount = 100;
        String[] headers = {"name", "code", "category", "price"};
        String[][] data = new String[rowCount][];

        for (int i = 0; i < rowCount; i++) {
            data[i] = new String[]{
                    "Product-" + i,
                    "P" + String.format("%04d", i),
                    "Category-" + (i % 5),
                    String.valueOf(10.0 + i * 0.5)
            };
        }

        ByteArrayInputStream stream = createExcel(headers, data);
        when(metaModelService.getModelFields("test_model")).thenReturn(List.of());

        // batchCreate succeeds
        when(dynamicDataService.batchCreate(eq("test_model"), anyList()))
                .thenReturn(new DynamicBatchResponse());

        ImportOptions options = new ImportOptions();
        ExcelImportResult result = importService.importExcel("test_model", stream, options);

        assertEquals(rowCount, result.getTotalRows());
        assertEquals(rowCount, result.getSuccessCount());
        assertEquals(0, result.getErrorCount());
        assertFalse(result.isHasErrors());

        // 100 rows in 1 batch (< 500 batch size)
        verify(dynamicDataService, times(1)).batchCreate(eq("test_model"), anyList());
        verify(dynamicDataService, never()).create(anyString(), anyMap());
    }

    // ========== Test 5: Completely empty workbook (no sheet data) ==========

    @Test
    void testCompletelyEmptyWorkbook() throws IOException {
        // Workbook with a sheet but zero rows (no header, no data)
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Sheet1");
            // No rows at all

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            ByteArrayInputStream stream = new ByteArrayInputStream(out.toByteArray());

            ImportOptions options = new ImportOptions();
            ExcelImportResult result = importService.importExcel("test_model", stream, options);

            assertEquals(0, result.getTotalRows());
            assertEquals(0, result.getSuccessCount());
            assertFalse(result.isHasErrors());

            verify(dynamicDataService, never()).create(anyString(), anyMap());
        }
    }

    // ========== Test 6: Null options defaults gracefully ==========

    @Test
    void testNullOptionsDefaultsGracefully() throws IOException {
        String[] headers = {"name"};
        String[][] data = {{"TestItem"}};

        ByteArrayInputStream stream = createExcel(headers, data);
        when(metaModelService.getModelFields("test_model")).thenReturn(List.of());

        when(dynamicDataService.batchCreate(eq("test_model"), anyList()))
                .thenReturn(new DynamicBatchResponse());

        // Pass null options — should use defaults
        ExcelImportResult result = importService.importExcel("test_model", stream, null);

        assertEquals(1, result.getTotalRows());
        assertEquals(1, result.getSuccessCount());
    }
}
