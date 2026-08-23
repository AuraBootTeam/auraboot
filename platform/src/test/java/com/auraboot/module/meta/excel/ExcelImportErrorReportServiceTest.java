package com.auraboot.module.meta.excel;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.infrastructure.storage.StorageProvider;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.module.meta.excel.entity.ImportJob;
import com.auraboot.module.meta.excel.mapper.ImportJobMapper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExcelImportErrorReportServiceTest {

    @Mock
    private ImportJobMapper importJobMapper;
    @Mock
    private StorageProvider storageProvider;
    @Mock
    private MetaModelService metaModelService;
    @Mock
    private I18nService i18nService;

    private ExcelImportErrorReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ExcelImportErrorReportService(
                importJobMapper, storageProvider, metaModelService, i18nService);
        FieldDefinition name = new FieldDefinition();
        name.setCode("name");
        name.setDisplayName("Name");
        FieldDefinition code = new FieldDefinition();
        code.setCode("code");
        code.setDisplayName("Customer code");
        org.mockito.Mockito.lenient().when(metaModelService.getModelFields("crm_lead"))
                .thenReturn(List.of(name, code));
        org.mockito.Mockito.lenient().when(i18nService.getValue(
                        "zh-CN", "import.validation.update_record_missing"))
                .thenReturn("未找到与“{field}”匹配的现有记录，请修正匹配值后重试");
    }

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void buildCorrectionWorkbook_shouldRetainOnlyFailedRowsAndRemainReuploadable() throws Exception {
        byte[] source = workbook(
                new String[]{"Name", "Customer code"},
                new String[][]{
                        {"Valid one", "L-001"},
                        {"Invalid reference", "L-002"},
                        {"Valid two", "L-003"},
                        {"Missing name", "L-004"}
                });
        List<ImportValidationError> errors = List.of(
                new ImportValidationError(3, "code", "Referenced record does not exist"),
                new ImportValidationError(5, "name", "Required field is missing"));

        byte[] report = reportService.buildCorrectionWorkbook("crm_lead", source, errors);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(report))) {
            assertEquals(2, workbook.getNumberOfSheets());
            Sheet importSheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            assertEquals("Invalid reference",
                    formatter.formatCellValue(importSheet.getRow(1).getCell(0)));
            assertEquals("Missing name",
                    formatter.formatCellValue(importSheet.getRow(2).getCell(0)));
            assertEquals(2, importSheet.getLastRowNum());
            assertNotNull(importSheet.getRow(1).getCell(1).getCellComment());
            assertNotNull(importSheet.getRow(2).getCell(0).getCellComment());

            Sheet errorsSheet = workbook.getSheet("Import errors");
            assertNotNull(errorsSheet);
            assertEquals("Customer code", errorsSheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("Name", errorsSheet.getRow(2).getCell(1).getStringCellValue());
        }

        List<java.util.Map<String, String>> reuploadRows = reportServiceRows(report);
        assertEquals(2, reuploadRows.size());
        assertEquals("L-002", reuploadRows.get(0).get("Customer code"));
        assertEquals("L-004", reuploadRows.get(1).get("Customer code"));
    }

    @Test
    void buildCorrectionWorkbook_shouldLocalizeUpdateMatchFailureAndAnnotateMatchColumn()
            throws Exception {
        byte[] source = workbook(
                new String[]{"Name", "Customer code"},
                new String[][]{
                        {"Updated", "L-001"},
                        {"Needs correction", "MISSING-001"}
                });

        byte[] report = reportService.buildCorrectionWorkbook(
                "crm_lead", source,
                List.of(new ImportValidationError(
                        3, null, "No existing record matches code=MISSING-001")),
                "zh-CN", ExcelImportErrorReportService.RowSelection.ERROR_ROWS);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(report))) {
            Sheet importSheet = workbook.getSheetAt(0);
            assertEquals(1, importSheet.getLastRowNum());
            assertNull(importSheet.getRow(1).getCell(0).getCellComment());
            assertNotNull(importSheet.getRow(1).getCell(1).getCellComment());
            String comment = importSheet.getRow(1).getCell(1).getCellComment().getString().getString();
            assertTrue(comment.contains("未找到与“Customer code”匹配的现有记录"));
            assertFalse(comment.contains("code=MISSING-001"));

            Sheet errorsSheet = workbook.getSheet("Import errors");
            assertEquals("Customer code", errorsSheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals(
                    "未找到与“Customer code”匹配的现有记录，请修正匹配值后重试",
                    errorsSheet.getRow(1).getCell(2).getStringCellValue());
        }
    }

    @Test
    void buildCorrectionWorkbook_shouldRetainEveryRowWhenValidationPreventedAllWrites()
            throws Exception {
        byte[] source = workbook(
                new String[]{"Name", "Customer code"},
                new String[][]{
                        {"Valid one", "L-001"},
                        {"Missing name", "L-002"},
                        {"Valid two", "L-003"}
                });

        byte[] report = reportService.buildCorrectionWorkbook(
                "crm_lead", source,
                List.of(new ImportValidationError(3, "name", "Required field is missing")),
                "zh-CN", ExcelImportErrorReportService.RowSelection.ALL_ROWS);

        List<java.util.Map<String, String>> reuploadRows = reportServiceRows(report);
        assertEquals(3, reuploadRows.size());
        assertEquals("L-001", reuploadRows.get(0).get("Customer code"));
        assertEquals("L-002", reuploadRows.get(1).get("Customer code"));
        assertEquals("L-003", reuploadRows.get(2).get("Customer code"));
    }

    @Test
    void buildCorrectionWorkbook_shouldRetainFailedAndUnprocessedRowsWhenImportStops()
            throws Exception {
        byte[] source = workbook(
                new String[]{"Name", "Customer code"},
                new String[][]{
                        {"Imported", "L-001"},
                        {"Failed", "L-002"},
                        {"Not processed", "L-003"}
                });

        byte[] report = reportService.buildCorrectionWorkbook(
                "crm_lead", source,
                List.of(new ImportValidationError(3, "code", "Import row could not be saved.")),
                "zh-CN", ExcelImportErrorReportService.RowSelection.FROM_FIRST_ERROR);

        List<java.util.Map<String, String>> reuploadRows = reportServiceRows(report);
        assertEquals(2, reuploadRows.size());
        assertEquals("L-002", reuploadRows.get(0).get("Customer code"));
        assertEquals("L-003", reuploadRows.get(1).get("Customer code"));
    }

    @Test
    void createReport_shouldStorePrivateObjectAndExposeOnlyAuthorizedApiUrl() throws Exception {
        MetaContext.setContext(7L, 42L, "tester", "tester");
        when(importJobMapper.insert(any(ImportJob.class))).thenAnswer(invocation -> {
            ImportJob job = invocation.getArgument(0);
            job.setId(99L);
            return 1;
        });
        byte[] source = workbook(new String[]{"Name", "Customer code"},
                new String[][]{{"Broken", "L-002"}});

        ExcelImportErrorReportService.ReportRegistration registration = reportService.createReport(
                "crm_lead", "lead-import.xlsx", "insert", source,
                List.of(new ImportValidationError(2, "code", "Invalid value")), 1, 0, "en-US",
                ExcelImportErrorReportService.RowSelection.ALL_ROWS);

        assertTrue(registration.taskId().length() >= 20);
        assertEquals("/api/meta/excel/import/crm_lead/error-report/" + registration.taskId(),
                registration.downloadUrl());
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(storageProvider).upload(key.capture(), any(InputStream.class), anyLong(),
                eq(ExcelImportErrorReportService.XLSX_CONTENT_TYPE));
        assertEquals("excel-import/error-reports/7/42/" + registration.taskId() + ".xlsx",
                key.getValue());

        ArgumentCaptor<ImportJob> job = ArgumentCaptor.forClass(ImportJob.class);
        verify(importJobMapper).insert(job.capture());
        assertEquals(registration.downloadUrl(), job.getValue().getErrorReportUrl());
        assertEquals(7L, job.getValue().getTenantId());
        assertEquals(42L, job.getValue().getCreatedBy());
        assertEquals("completed", job.getValue().getStatus());
        assertEquals(1, job.getValue().getErrorRows());
        assertTrue(job.getValue().getErrorDetails().contains("\"rowNumber\":2"));
        assertTrue(job.getValue().getErrorDetails().contains("Invalid value"));
    }

    @Test
    void findDownload_shouldFailClosedWhenScopedJobIsNotVisible() {
        MetaContext.setContext(7L, 99L, "other", "other");
        when(importJobMapper.selectOne(any())).thenReturn(null);

        assertNull(reportService.findDownload("crm_lead", "01KREPORT"));

        verify(storageProvider, never()).exists(anyString());
        verify(storageProvider, never()).download(anyString());
    }

    @Test
    void findDownload_shouldNotExposeAnExpiredReport() throws Exception {
        setRetentionDays(7);
        MetaContext.setContext(7L, 42L, "tester", "tester");
        ImportJob job = reportJob("01KEXPIRED", LocalDateTime.now().minusDays(8));
        when(importJobMapper.selectOne(any())).thenReturn(job);

        assertNull(reportService.findDownload("crm_lead", job.getPid()));

        verify(storageProvider, never()).exists(anyString());
        verify(storageProvider, never()).download(anyString());
    }

    @Test
    void cleanupExpiredReports_shouldDeleteObjectAndKeepImportHistory() throws Exception {
        setRetentionDays(7);
        ImportJob job = reportJob("01KCLEANUP", LocalDateTime.now().minusDays(8));
        when(importJobMapper.findExpiredReports(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(job));
        when(importJobMapper.clearErrorReport(eq(job.getId()), any(LocalDateTime.class)))
                .thenReturn(1);

        assertEquals(1, reportService.cleanupExpiredReports());

        verify(storageProvider).delete("excel-import/error-reports/7/42/01KCLEANUP.xlsx");
        verify(importJobMapper).clearErrorReport(eq(job.getId()), any(LocalDateTime.class));
        verify(importJobMapper, never()).deleteById(anyLong());
    }

    private List<java.util.Map<String, String>> reportServiceRows(byte[] report) throws Exception {
        // The production parser reads only the first sheet. Sparse source row positions do not
        // matter; successful rows were removed, so only correction rows can be re-imported.
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(report))) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            List<java.util.Map<String, String>> rows = new java.util.ArrayList<>();
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) {
                    continue;
                }
                String name = formatter.formatCellValue(row.getCell(0));
                String code = formatter.formatCellValue(row.getCell(1));
                if (!name.isBlank() || !code.isBlank()) {
                    rows.add(java.util.Map.of("Name", name, "Customer code", code));
                }
            }
            return rows;
        }
    }

    private byte[] workbook(String[] headers, String[][] data) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Import");
            Row header = sheet.createRow(0);
            for (int column = 0; column < headers.length; column++) {
                header.createCell(column).setCellValue(headers[column]);
            }
            for (int rowIndex = 0; rowIndex < data.length; rowIndex++) {
                Row row = sheet.createRow(rowIndex + 1);
                for (int column = 0; column < data[rowIndex].length; column++) {
                    row.createCell(column).setCellValue(data[rowIndex][column]);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private ImportJob reportJob(String taskId, LocalDateTime completedAt) {
        ImportJob job = new ImportJob();
        job.setId(91L);
        job.setPid(taskId);
        job.setTenantId(7L);
        job.setCreatedBy(42L);
        job.setModelCode("crm_lead");
        job.setCompletedAt(completedAt);
        job.setErrorReportUrl("/api/meta/excel/import/crm_lead/error-report/" + taskId);
        return job;
    }

    private void setRetentionDays(int days) throws Exception {
        Field retention = ExcelImportErrorReportService.class.getDeclaredField("retentionDays");
        retention.setAccessible(true);
        retention.setInt(reportService, days);
    }
}
