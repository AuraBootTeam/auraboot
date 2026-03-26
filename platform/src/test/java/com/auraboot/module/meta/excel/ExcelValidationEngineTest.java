package com.auraboot.module.meta.excel;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ExcelValidationEngine.
 */
@ExtendWith(MockitoExtension.class)
class ExcelValidationEngineTest {

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private MetaFieldService metaFieldService;

    @Mock
    private DynamicDataService dynamicDataService;

    private ExcelValidationEngine validationEngine;

    @BeforeEach
    void setUp() {
        validationEngine = new ExcelValidationEngine(metaModelService, metaFieldService, dynamicDataService);
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

    // ==================== Required field tests ====================

    @Test
    void validate_shouldDetectMissingRequiredFields() throws IOException {
        // Given: a model with required fields
        var fields = List.of(
                FieldDefinition.builder().code("name").displayName("Name")
                        .dataType("text").required(true).build(),
                FieldDefinition.builder().code("code").displayName("Code")
                        .dataType("text").required(true).build(),
                FieldDefinition.builder().code("description").displayName("Description")
                        .dataType("text").required(false).build()
        );
        when(metaModelService.getModelFields("test_model")).thenReturn(fields);

        // Row 1 has empty "name" (required), row 2 has empty "code" (required)
        String[] headers = {"name", "code", "description"};
        String[][] data = {
                {"", "c001", "desc 1"},      // missing required name
                {"Item B", "", "desc 2"},     // missing required code
                {"Item C", "c003", "desc 3"}  // valid
        };
        ByteArrayInputStream stream = createExcel(headers, data);

        // When
        ValidationReport report = validationEngine.validate("test_model", stream);

        // Then
        assertEquals(3, report.getTotalRows());
        assertEquals(1, report.getValidRows());
        assertFalse(report.isValid());
        assertEquals(2, report.getErrors().size());

        // First error: row 2, field "name"
        assertEquals(2, report.getErrors().get(0).getRowNumber());
        assertEquals("name", report.getErrors().get(0).getFieldCode());
        assertTrue(report.getErrors().get(0).getMessage().contains("Required"));

        // Second error: row 3, field "code"
        assertEquals(3, report.getErrors().get(1).getRowNumber());
        assertEquals("code", report.getErrors().get(1).getFieldCode());
    }

    // ==================== Type mismatch tests ====================

    @Test
    void validate_shouldDetectTypeMismatch() throws IOException {
        // Given: a model with typed fields
        var fields = List.of(
                FieldDefinition.builder().code("name").displayName("Name")
                        .dataType("text").build(),
                FieldDefinition.builder().code("quantity").displayName("Quantity")
                        .dataType("integer").build(),
                FieldDefinition.builder().code("price").displayName("Price")
                        .dataType("decimal").build(),
                FieldDefinition.builder().code("active").displayName("Active")
                        .dataType("boolean").build(),
                FieldDefinition.builder().code("start_date").displayName("Start Date")
                        .dataType("date").build()
        );
        when(metaModelService.getModelFields("test_model")).thenReturn(fields);

        String[] headers = {"name", "quantity", "price", "active", "start_date"};
        String[][] data = {
                {"Item A", "not_a_number", "10.5", "true", "2024-01-15"},  // bad quantity
                {"Item B", "100", "abc", "false", "2024-02-20"},           // bad price
                {"Item C", "50", "25.0", "maybe", "2024-03-10"},           // bad boolean
                {"Item D", "30", "15.0", "yes", "not-a-date"},             // bad date
                {"Item E", "20", "5.0", "1", "2024-05-01"}                 // all valid
        };
        ByteArrayInputStream stream = createExcel(headers, data);

        // When
        ValidationReport report = validationEngine.validate("test_model", stream);

        // Then
        assertEquals(5, report.getTotalRows());
        assertEquals(1, report.getValidRows()); // only last row is valid
        assertEquals(4, report.getErrors().size());

        // Check each type mismatch error
        assertEquals("quantity", report.getErrors().get(0).getFieldCode());
        assertTrue(report.getErrors().get(0).getMessage().contains("integer"));

        assertEquals("price", report.getErrors().get(1).getFieldCode());
        assertTrue(report.getErrors().get(1).getMessage().contains("decimal"));

        assertEquals("active", report.getErrors().get(2).getFieldCode());
        assertTrue(report.getErrors().get(2).getMessage().contains("boolean"));

        assertEquals("start_date", report.getErrors().get(3).getFieldCode());
        assertTrue(report.getErrors().get(3).getMessage().contains("date"));
    }

    // ==================== Duplicate detection tests ====================

    @Test
    void validate_shouldDetectDuplicates() throws IOException {
        // Given: a model with a unique field
        var fields = List.of(
                FieldDefinition.builder().code("code").displayName("Code")
                        .dataType("text").unique(true).build(),
                FieldDefinition.builder().code("name").displayName("Name")
                        .dataType("text").build()
        );
        when(metaModelService.getModelFields("test_model")).thenReturn(fields);

        String[] headers = {"code", "name"};
        String[][] data = {
                {"c001", "Item A"},
                {"c002", "Item B"},
                {"c001", "Item C"},  // duplicate of row 2
                {"c003", "Item D"},
                {"c002", "Item E"}   // duplicate of row 3
        };
        ByteArrayInputStream stream = createExcel(headers, data);

        // When
        ValidationReport report = validationEngine.validate("test_model", stream);

        // Then
        assertEquals(5, report.getTotalRows());
        assertEquals(3, report.getValidRows());
        assertFalse(report.isValid());
        assertEquals(2, report.getErrors().size());

        // First duplicate: row 4, "c001" first seen at row 2
        assertEquals(4, report.getErrors().get(0).getRowNumber());
        assertEquals("code", report.getErrors().get(0).getFieldCode());
        assertTrue(report.getErrors().get(0).getMessage().contains("Duplicate"));
        assertTrue(report.getErrors().get(0).getMessage().contains("row 2"));

        // Second duplicate: row 6, "c002" first seen at row 3
        assertEquals(6, report.getErrors().get(1).getRowNumber());
        assertEquals("code", report.getErrors().get(1).getFieldCode());
        assertTrue(report.getErrors().get(1).getMessage().contains("row 3"));
    }

    // ==================== Clean data tests ====================

    @Test
    void validate_shouldReturnValidForCleanData() throws IOException {
        // Given: a model with various field types
        var fields = List.of(
                FieldDefinition.builder().code("name").displayName("Name")
                        .dataType("text").required(true).build(),
                FieldDefinition.builder().code("code").displayName("Code")
                        .dataType("text").required(true).unique(true).build(),
                FieldDefinition.builder().code("quantity").displayName("Quantity")
                        .dataType("integer").build(),
                FieldDefinition.builder().code("price").displayName("Price")
                        .dataType("decimal").build(),
                FieldDefinition.builder().code("active").displayName("Active")
                        .dataType("boolean").build()
        );
        when(metaModelService.getModelFields("test_model")).thenReturn(fields);

        String[] headers = {"name", "code", "quantity", "price", "active"};
        String[][] data = {
                {"Widget A", "w001", "100", "10.50", "true"},
                {"Widget B", "w002", "200", "20.00", "false"},
                {"Widget C", "w003", "50", "5.99", "yes"}
        };
        ByteArrayInputStream stream = createExcel(headers, data);

        // When
        ValidationReport report = validationEngine.validate("test_model", stream);

        // Then
        assertEquals(3, report.getTotalRows());
        assertEquals(3, report.getValidRows());
        assertTrue(report.isValid());
        assertTrue(report.getErrors().isEmpty());
        assertTrue(report.getWarnings().isEmpty());
    }

    // ==================== isTypeValid unit tests ====================

    @Test
    void isTypeValid_integerTypes() {
        assertTrue(validationEngine.isTypeValid("integer", "42"));
        assertTrue(validationEngine.isTypeValid("int", "-100"));
        assertTrue(validationEngine.isTypeValid("integer", "0"));
        assertFalse(validationEngine.isTypeValid("integer", "abc"));
        assertFalse(validationEngine.isTypeValid("integer", "12.5"));
    }

    @Test
    void isTypeValid_decimalTypes() {
        assertTrue(validationEngine.isTypeValid("decimal", "42.5"));
        assertTrue(validationEngine.isTypeValid("float", "-100.0"));
        assertTrue(validationEngine.isTypeValid("double", "0"));
        assertTrue(validationEngine.isTypeValid("number", "3.14159"));
        assertFalse(validationEngine.isTypeValid("decimal", "abc"));
    }

    @Test
    void isTypeValid_booleanType() {
        assertTrue(validationEngine.isTypeValid("boolean", "true"));
        assertTrue(validationEngine.isTypeValid("boolean", "false"));
        assertTrue(validationEngine.isTypeValid("boolean", "1"));
        assertTrue(validationEngine.isTypeValid("boolean", "0"));
        assertTrue(validationEngine.isTypeValid("boolean", "yes"));
        assertTrue(validationEngine.isTypeValid("boolean", "no"));
        assertFalse(validationEngine.isTypeValid("boolean", "maybe"));
        assertFalse(validationEngine.isTypeValid("boolean", "2"));
    }

    @Test
    void isTypeValid_dateType() {
        assertTrue(validationEngine.isTypeValid("date", "2024-01-15"));
        assertTrue(validationEngine.isTypeValid("date", "2024-12-31"));
        assertFalse(validationEngine.isTypeValid("date", "not-a-date"));
        assertFalse(validationEngine.isTypeValid("date", "01/15/2024"));
        assertFalse(validationEngine.isTypeValid("date", "2024-13-01")); // invalid month
    }

    @Test
    void isTypeValid_textAlwaysValid() {
        assertTrue(validationEngine.isTypeValid("text", "anything"));
        assertTrue(validationEngine.isTypeValid("string", "12345"));
        assertTrue(validationEngine.isTypeValid("varchar", ""));
        assertTrue(validationEngine.isTypeValid(null, "value"));
    }

    @Test
    void isTypeValid_blankValueAlwaysValid() {
        assertTrue(validationEngine.isTypeValid("integer", ""));
        assertTrue(validationEngine.isTypeValid("integer", "  "));
        assertTrue(validationEngine.isTypeValid("integer", null));
    }

    // ==================== Empty file tests ====================

    @Test
    void validate_emptyFile() throws IOException {
        var fields = List.of(
                FieldDefinition.builder().code("name").dataType("text").build()
        );
        when(metaModelService.getModelFields("test_model")).thenReturn(fields);

        // Create Excel with header only
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("name");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            ByteArrayInputStream stream = new ByteArrayInputStream(out.toByteArray());

            ValidationReport report = validationEngine.validate("test_model", stream);

            assertEquals(0, report.getTotalRows());
            assertEquals(0, report.getValidRows());
            assertTrue(report.isValid());
        }
    }

    @Test
    void validate_noFieldDefinitions() throws IOException {
        when(metaModelService.getModelFields("unknown_model")).thenReturn(List.of());

        String[] headers = {"name"};
        String[][] data = {{"Test"}};
        ByteArrayInputStream stream = createExcel(headers, data);

        ValidationReport report = validationEngine.validate("unknown_model", stream);

        assertFalse(report.isValid());
        assertEquals(1, report.getErrors().size());
        assertTrue(report.getErrors().get(0).getMessage().contains("no field definitions"));
    }

    // ==================== Header mapping with displayName ====================

    @Test
    void validate_worksWithDisplayNameHeaders() throws IOException {
        var fields = List.of(
                FieldDefinition.builder().code("pe_name").displayName("Name")
                        .dataType("text").required(true).build(),
                FieldDefinition.builder().code("pe_qty").displayName("Quantity")
                        .dataType("integer").build()
        );
        when(metaModelService.getModelFields("test_model")).thenReturn(fields);

        // Excel uses displayName headers
        String[] headers = {"Name", "Quantity"};
        String[][] data = {
                {"Widget A", "10"},
                {"", "abc"}      // missing required name + bad integer
        };
        ByteArrayInputStream stream = createExcel(headers, data);

        ValidationReport report = validationEngine.validate("test_model", stream);

        assertEquals(2, report.getTotalRows());
        assertEquals(1, report.getValidRows());
        assertEquals(2, report.getErrors().size()); // 1 required + 1 type mismatch
    }

    // ==================== Auto fields excluded ====================

    @Test
    void validate_ignoresAutoGeneratedFields() throws IOException {
        var fields = List.of(
                FieldDefinition.builder().code("id").dataType("integer").primaryKey(true).build(),
                FieldDefinition.builder().code("pid").dataType("text").build(),
                FieldDefinition.builder().code("created_at").dataType("datetime").build(),
                FieldDefinition.builder().code("tenant_id").dataType("integer").build(),
                FieldDefinition.builder().code("name").displayName("Name")
                        .dataType("text").required(true).build()
        );
        when(metaModelService.getModelFields("test_model")).thenReturn(fields);

        // Excel only has "name" column; auto-fields are not in the file
        String[] headers = {"name"};
        String[][] data = {{"Item A"}};
        ByteArrayInputStream stream = createExcel(headers, data);

        ValidationReport report = validationEngine.validate("test_model", stream);

        assertTrue(report.isValid());
        assertEquals(1, report.getValidRows());
    }
}
