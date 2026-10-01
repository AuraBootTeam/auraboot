package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.DataExportRequest;
import com.auraboot.framework.meta.dto.DynamicDataExportRequest;
import com.auraboot.framework.meta.dto.ExportResult;
import com.auraboot.framework.meta.dto.ExportTaskDTO;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.ExportTaskService;
import com.auraboot.module.exchange.profile.ExportProfileResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicControllerExportDownloadTest {

    @TempDir
    Path tempDir;

    @Test
    void exportDataParsesLowercaseCsvFormat() {
        DynamicController controller = new DynamicController();
        DynamicDataService dynamicDataService = mock(DynamicDataService.class);
        MetaModelService metaModelService = mock(MetaModelService.class);
        ExportTaskService exportTaskService = mock(ExportTaskService.class);
        Path csv = tempDir.resolve("cr_crawled_document_export_1.csv");

        when(metaModelService.getModelDefinition("cr_crawled_document")).thenReturn(Optional.of(
                ModelDefinition.builder().code("cr_crawled_document").build()));
        when(dynamicDataService.exportData(eq("cr_crawled_document"), any(DataExportRequest.class)))
                .thenReturn(ExportResult.builder()
                        .success(true)
                        .filePath(csv.toString())
                        .recordCount(1L)
                        .build());
        ReflectionTestUtils.setField(controller, "dynamicDataService", dynamicDataService);
        ReflectionTestUtils.setField(controller, "metaModelService", metaModelService);
        ReflectionTestUtils.setField(controller, "exportTaskService", exportTaskService);
        ReflectionTestUtils.setField(controller, "exportProfileResolver",
                new ExportProfileResolver(metaModelService));
        when(exportTaskService.registerModelExport(eq("cr_crawled_document"),
                eq("cr_crawled_document:default-export"), any(), any()))
                .thenReturn(task("export-csv"));

        ApiResponse<Map<String, Object>> response = controller.exportData(
                "cr_crawled_document",
                exportRequest("csv", List.of(), null));

        ArgumentCaptor<DataExportRequest> requestCaptor = ArgumentCaptor.forClass(DataExportRequest.class);
        verify(dynamicDataService).exportData(eq("cr_crawled_document"), requestCaptor.capture());
        assertThat(requestCaptor.getValue().getFormat()).isEqualTo(DataExportRequest.ExportFormat.CSV);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().get("downloadUrl").toString())
                .isEqualTo("/api/dynamic/cr_crawled_document/exports/export-csv/download");
    }

    @Test
    void exportDataPreservesArrayConditionsAndKeyword() {
        DynamicController controller = new DynamicController();
        DynamicDataService dynamicDataService = mock(DynamicDataService.class);
        MetaModelService metaModelService = mock(MetaModelService.class);
        ExportTaskService exportTaskService = mock(ExportTaskService.class);
        Path xlsx = tempDir.resolve("crm_opportunity_common_export.xlsx");

        when(metaModelService.getModelDefinition("crm_opportunity_common")).thenReturn(Optional.of(
                ModelDefinition.builder().code("crm_opportunity_common").build()));
        when(dynamicDataService.exportData(eq("crm_opportunity_common"), any(DataExportRequest.class)))
                .thenReturn(ExportResult.builder()
                        .success(true)
                        .filePath(xlsx.toString())
                        .recordCount(2L)
                        .build());
        ReflectionTestUtils.setField(controller, "dynamicDataService", dynamicDataService);
        ReflectionTestUtils.setField(controller, "metaModelService", metaModelService);
        ReflectionTestUtils.setField(controller, "exportTaskService", exportTaskService);
        ReflectionTestUtils.setField(controller, "exportProfileResolver",
                new ExportProfileResolver(metaModelService));
        when(exportTaskService.registerModelExport(eq("crm_opportunity_common"),
                eq("crm_opportunity_common:default-export"), any(), any()))
                .thenReturn(task("export-xlsx"));

        ApiResponse<Map<String, Object>> response = controller.exportData(
                "crm_opportunity_common",
                exportRequest("excel", List.of(
                        condition("crm_opp_forecast_category", "IN", List.of("commit", "best_case")),
                        condition("crm_opp_expected_close_date", "BETWEEN",
                                List.of("2026-08-01", "2026-08-31"))), "华东"));

        ArgumentCaptor<DataExportRequest> requestCaptor = ArgumentCaptor.forClass(DataExportRequest.class);
        verify(dynamicDataService).exportData(eq("crm_opportunity_common"), requestCaptor.capture());
        DataExportRequest request = requestCaptor.getValue();

        assertThat(request.getKeyword()).isEqualTo("华东");
        assertThat(request.getConditions()).hasSize(2);
        assertThat(request.getConditions().get(0).getValues()).containsExactly("commit", "best_case");
        assertThat(request.getConditions().get(1).getValues()).containsExactly("2026-08-01", "2026-08-31");
        assertThat(response.getData().get("recordCount")).isEqualTo(2L);
    }

    @Test
    void selectedExportAddsServerOwnedPidCondition() {
        DynamicController controller = new DynamicController();
        DynamicDataService dynamicDataService = mock(DynamicDataService.class);
        MetaModelService metaModelService = mock(MetaModelService.class);
        ExportTaskService exportTaskService = mock(ExportTaskService.class);
        when(metaModelService.getModelDefinition("customer")).thenReturn(Optional.of(
                ModelDefinition.builder().code("customer").build()));
        when(dynamicDataService.exportData(eq("customer"), any(DataExportRequest.class)))
                .thenReturn(ExportResult.builder()
                        .success(true)
                        .filePath(tempDir.resolve("customer.xlsx").toString())
                        .recordCount(2L)
                        .build());
        ReflectionTestUtils.setField(controller, "dynamicDataService", dynamicDataService);
        ReflectionTestUtils.setField(controller, "metaModelService", metaModelService);
        ReflectionTestUtils.setField(controller, "exportTaskService", exportTaskService);
        ReflectionTestUtils.setField(controller, "exportProfileResolver",
                new ExportProfileResolver(metaModelService));
        when(exportTaskService.registerModelExport(eq("customer"),
                eq("customer:default-export"), any(), any()))
                .thenReturn(task("export-selected"));

        DynamicDataExportRequest request = exportRequest("excel", List.of(), null);
        request.setScope(DynamicDataExportRequest.Scope.selected);
        request.setSelectedPids(List.of("pid-a", "pid-b", "pid-a"));

        controller.exportData("customer", request);

        ArgumentCaptor<DataExportRequest> requestCaptor = ArgumentCaptor.forClass(DataExportRequest.class);
        verify(dynamicDataService).exportData(eq("customer"), requestCaptor.capture());
        assertThat(requestCaptor.getValue().getConditions()).hasSize(1);
        assertThat(requestCaptor.getValue().getConditions().getFirst().getFieldName()).isEqualTo("pid");
        assertThat(requestCaptor.getValue().getConditions().getFirst().getValues())
                .containsExactly("pid-a", "pid-b");
    }

    @Test
    void invalidFilterOperatorFailsClosedInsteadOfExportingAnUnfilteredDataset() {
        DynamicController controller = new DynamicController();
        DynamicDataService dynamicDataService = mock(DynamicDataService.class);
        MetaModelService metaModelService = mock(MetaModelService.class);
        when(metaModelService.getModelDefinition("customer")).thenReturn(Optional.of(
                ModelDefinition.builder().code("customer").build()));
        ReflectionTestUtils.setField(controller, "dynamicDataService", dynamicDataService);
        ReflectionTestUtils.setField(controller, "metaModelService", metaModelService);
        ReflectionTestUtils.setField(controller, "exportProfileResolver",
                new ExportProfileResolver(metaModelService));

        DynamicDataExportRequest request = exportRequest("excel",
                List.of(condition("status", "DROP_TABLE", "active")), null);

        assertThatThrownBy(() -> controller.exportData("customer", request))
                .hasMessageContaining("Invalid export filter operator");
        org.mockito.Mockito.verifyNoInteractions(dynamicDataService);
    }

    private static DynamicDataExportRequest exportRequest(
            String format, List<DynamicDataExportRequest.Condition> conditions, String keyword) {
        DynamicDataExportRequest request = new DynamicDataExportRequest();
        request.setScope(DynamicDataExportRequest.Scope.filtered);
        request.setFormat(format);
        request.setConditions(conditions);
        request.setKeyword(keyword);
        return request;
    }

    private static DynamicDataExportRequest.Condition condition(
            String field, String operator, Object value) {
        DynamicDataExportRequest.Condition condition = new DynamicDataExportRequest.Condition();
        condition.setField(field);
        condition.setOperator(operator);
        condition.setValue(value);
        return condition;
    }

    @Test
    void downloadExportUsesCsvHeadersForPrivateArtifact() throws Exception {
        DynamicController controller = downloadController("cr_crawled_document", "task-csv",
                ".csv", "Title,URL\nPump,http://127.0.0.1/item\n".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.downloadExport("cr_crawled_document", "task-csv", response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(response.getHeader("Content-Disposition"))
                .contains("cr_crawled_document_export.csv");
        assertThat(response.getContentAsString()).contains("Pump");
    }

    @Test
    void downloadExportUsesExcelHeadersForPrivateArtifact() throws Exception {
        DynamicController controller = downloadController("cr_crawled_document", "task-xlsx",
                ".xlsx", new byte[] { 0x50, 0x4b, 0x03, 0x04 });
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.downloadExport("cr_crawled_document", "task-xlsx", response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.getHeader("Content-Disposition"))
                .contains("cr_crawled_document_export.xlsx");
        assertThat(response.getContentAsByteArray()).containsExactly(0x50, 0x4b, 0x03, 0x04);
    }

    private static DynamicController downloadController(
            String modelCode, String taskPid, String extension, byte[] content) {
        DynamicController controller = new DynamicController();
        MetaModelService metaModelService = mock(MetaModelService.class);
        ExportTaskService exportTaskService = mock(ExportTaskService.class);
        when(metaModelService.getModelDefinition(modelCode)).thenReturn(Optional.empty());
        when(exportTaskService.openModelArtifact(taskPid, modelCode))
                .thenReturn(new ExportTaskService.ExportArtifactDownload(
                        new java.io.ByteArrayInputStream(content), content.length, extension));
        ReflectionTestUtils.setField(controller, "metaModelService", metaModelService);
        ReflectionTestUtils.setField(controller, "exportTaskService", exportTaskService);
        return controller;
    }

    private static ExportTaskDTO task(String pid) {
        ExportTaskDTO task = new ExportTaskDTO();
        task.setPid(pid);
        return task;
    }
}
