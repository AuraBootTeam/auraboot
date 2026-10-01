package com.auraboot.module.meta.excel;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.TypeSystemManager;
import com.auraboot.module.meta.excel.entity.ImportJob;
import com.auraboot.module.meta.excel.mapper.ImportJobMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentImportServiceTest {

    @Mock
    private DocumentImportProfileResolver profiles;
    @Mock
    private MetaModelService models;
    @Mock
    private CommandExecutor commands;
    @Mock
    private ImportJobMapper importJobs;

    private DocumentImportService service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(7L, 42L, "owner", "owner");
        service = new DocumentImportService(profiles, models, commands,
                new TypeSystemManager(), new ObjectMapper(), importJobs);
        org.mockito.Mockito.lenient().when(importJobs.insert(any(ImportJob.class))).thenAnswer(invocation -> {
            ImportJob job = invocation.getArgument(0);
            job.setId(99L);
            return 1;
        });
        org.mockito.Mockito.lenient().when(importJobs.updateById(any(ImportJob.class))).thenReturn(1);
        when(profiles.requireEnabled("purchase_order")).thenReturn(profile());
        org.mockito.Mockito.lenient().when(models.getModelFields("purchase_order")).thenReturn(List.of(
                FieldDefinition.builder().code("order_no").build(),
                FieldDefinition.builder().code("supplier").build()));
        org.mockito.Mockito.lenient().when(models.getModelFields("purchase_order_line")).thenReturn(List.of(
                FieldDefinition.builder().code("order_no").build(),
                FieldDefinition.builder().code("sku").build(),
                FieldDefinition.builder().code("qty").dataType("integer").build()));
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void executesExactlyOneAggregateCommandPerDocument() throws Exception {
        byte[] workbook = workbook(
                new String[][]{{"PO-1", "Supplier A"}, {"PO-2", "Supplier B"}},
                new String[][]{{"PO-1", "SKU-1", "2"}, {"PO-1", "SKU-2", "3"},
                        {"PO-2", "SKU-3", "1"}});

        ExcelImportResult result = service.importWorkbook("purchase_order",
                new ByteArrayInputStream(workbook), false, false);

        assertThat(result.getSuccessCount()).isEqualTo(2);
        ArgumentCaptor<CommandExecuteRequest> requests = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(commands, org.mockito.Mockito.times(2))
                .execute(eq("purchase_order:import"), requests.capture());
        assertThat((List<?>) requests.getAllValues().getFirst().getPayload().get("lines")).hasSize(2);
        assertThat(requests.getAllValues().getFirst().getAuditContext())
                .containsEntry("profileCode", "purchase-order-standard-import")
                .containsEntry("groupKey", "PO-1");
        assertThat(requests.getAllValues()).extracting(CommandExecuteRequest::getClientRequestId)
                .allMatch(id -> id.startsWith("excel-document:7:purchase-order-standard-import:"));
        ArgumentCaptor<ImportJob> persisted = ArgumentCaptor.forClass(ImportJob.class);
        verify(importJobs).updateById(persisted.capture());
        assertThat(persisted.getValue().getImportMode())
                .isEqualTo("DOCUMENT:purchase-order-standard-import");
        assertThat(persisted.getValue().getStatus()).isEqualTo("completed");
        assertThat(persisted.getValue().getProcessedRows()).isEqualTo(2);
        assertThat(persisted.getValue().getErrorRows()).isZero();
    }

    @Test
    void dryRunPlansDocumentsWithoutCallingCommand() throws Exception {
        ExcelImportResult result = service.importWorkbook("purchase_order",
                new ByteArrayInputStream(workbook(
                        new String[][]{{"PO-1", "Supplier A"}},
                        new String[][]{{"PO-1", "SKU-1", "2"}})), true, false);

        assertThat(result.getTotalRows()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isZero();
        verify(commands, never()).execute(any(), any());
    }

    @Test
    void rejectsOrphanLineBeforeAnyBusinessWrite() throws Exception {
        ExcelImportResult result = service.importWorkbook("purchase_order",
                new ByteArrayInputStream(workbook(
                        new String[][]{{"PO-1", "Supplier A"}},
                        new String[][]{{"PO-X", "SKU-1", "2"}})), false, true);

        assertThat(result.isHasErrors()).isTrue();
        assertThat(result.getErrors()).extracting(ImportValidationError::getMessage)
                .anyMatch(message -> message.contains("unknown document group"));
        verify(commands, never()).execute(any(), any());
        ArgumentCaptor<ImportJob> persisted = ArgumentCaptor.forClass(ImportJob.class);
        verify(importJobs).updateById(persisted.capture());
        assertThat(persisted.getValue().getTotalRows()).isEqualTo(1);
        assertThat(persisted.getValue().getProcessedRows()).isEqualTo(1);
        assertThat(persisted.getValue().getErrorRows()).isEqualTo(1);
    }

    @Test
    void generatedTemplateUsesDeclaredStandardSheetsAndColumns() throws Exception {
        var path = service.generateTemplate("purchase_order");
        try (XSSFWorkbook workbook = new XSSFWorkbook(Files.newInputStream(path))) {
            assertThat(workbook.getSheet("Orders").getRow(0).getCell(0).getStringCellValue())
                    .isEqualTo("order_no");
            assertThat(workbook.getSheet("Lines").getRow(0).getCell(2).getStringCellValue())
                    .isEqualTo("qty");
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void terminalPersistenceFailureIsNotRetriedAsABusinessImportFailure() throws Exception {
        when(importJobs.updateById(any(ImportJob.class))).thenReturn(0);
        byte[] workbook = workbook(
                new String[][]{{"PO-1", "Supplier A"}},
                new String[][]{{"PO-1", "SKU-1", "2"}});

        assertThatThrownBy(() -> service.importWorkbook("purchase_order",
                new ByteArrayInputStream(workbook), false, false))
                .hasMessageContaining("terminal document import task");

        verify(importJobs, org.mockito.Mockito.times(1)).updateById(any(ImportJob.class));
    }

    private byte[] workbook(String[][] headers, String[][] lines) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var headerSheet = workbook.createSheet("Orders");
            var headerRow = headerSheet.createRow(0);
            headerRow.createCell(0).setCellValue("order_no");
            headerRow.createCell(1).setCellValue("supplier");
            for (int row = 0; row < headers.length; row++) {
                var target = headerSheet.createRow(row + 1);
                for (int cell = 0; cell < headers[row].length; cell++) {
                    target.createCell(cell).setCellValue(headers[row][cell]);
                }
            }
            var lineSheet = workbook.createSheet("Lines");
            var lineHeader = lineSheet.createRow(0);
            lineHeader.createCell(0).setCellValue("order_no");
            lineHeader.createCell(1).setCellValue("sku");
            lineHeader.createCell(2).setCellValue("qty");
            for (int row = 0; row < lines.length; row++) {
                var target = lineSheet.createRow(row + 1);
                for (int cell = 0; cell < lines[row].length; cell++) {
                    target.createCell(cell).setCellValue(lines[row][cell]);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static DocumentImportProfile profile() {
        return DocumentImportProfile.builder()
                .code("purchase-order-standard-import")
                .modelCode("purchase_order")
                .command("purchase_order:import")
                .headerSheet("Orders")
                .lineSheet("Lines")
                .groupBy("order_no")
                .lineGroupField("order_no")
                .lineModelCode("purchase_order_line")
                .linesPayloadField("lines")
                .headerFields(List.of("order_no", "supplier"))
                .lineFields(List.of("order_no", "sku", "qty"))
                .requireLines(true)
                .build();
    }
}
