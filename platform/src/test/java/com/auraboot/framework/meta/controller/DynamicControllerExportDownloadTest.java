package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.DataExportRequest;
import com.auraboot.framework.meta.dto.ExportResult;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
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
        Path csv = tempDir.resolve("cr_crawled_document_export_1.csv");

        when(metaModelService.getModelDefinition("cr_crawled_document")).thenReturn(Optional.empty());
        when(dynamicDataService.exportData(eq("cr_crawled_document"), any(DataExportRequest.class)))
                .thenReturn(ExportResult.builder()
                        .success(true)
                        .filePath(csv.toString())
                        .recordCount(1L)
                        .build());
        ReflectionTestUtils.setField(controller, "dynamicDataService", dynamicDataService);
        ReflectionTestUtils.setField(controller, "metaModelService", metaModelService);

        ApiResponse<Map<String, Object>> response = controller.exportData(
                "cr_crawled_document",
                Map.of("format", "csv"));

        ArgumentCaptor<DataExportRequest> requestCaptor = ArgumentCaptor.forClass(DataExportRequest.class);
        verify(dynamicDataService).exportData(eq("cr_crawled_document"), requestCaptor.capture());
        assertThat(requestCaptor.getValue().getFormat()).isEqualTo(DataExportRequest.ExportFormat.CSV);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().get("downloadUrl").toString()).contains("cr_crawled_document_export_1.csv");
    }

    @Test
    void exportDataPreservesArrayConditionsAndKeyword() {
        DynamicController controller = new DynamicController();
        DynamicDataService dynamicDataService = mock(DynamicDataService.class);
        MetaModelService metaModelService = mock(MetaModelService.class);
        Path xlsx = tempDir.resolve("crm_opportunity_common_export.xlsx");

        when(metaModelService.getModelDefinition("crm_opportunity_common")).thenReturn(Optional.empty());
        when(dynamicDataService.exportData(eq("crm_opportunity_common"), any(DataExportRequest.class)))
                .thenReturn(ExportResult.builder()
                        .success(true)
                        .filePath(xlsx.toString())
                        .recordCount(2L)
                        .build());
        ReflectionTestUtils.setField(controller, "dynamicDataService", dynamicDataService);
        ReflectionTestUtils.setField(controller, "metaModelService", metaModelService);

        ApiResponse<Map<String, Object>> response = controller.exportData(
                "crm_opportunity_common",
                Map.of(
                        "format", "excel",
                        "keyword", "华东",
                        "conditions", List.of(
                                Map.of("field", "crm_opp_forecast_category", "operator", "IN",
                                        "value", List.of("commit", "best_case")),
                                Map.of("field", "crm_opp_expected_close_date", "operator", "BETWEEN",
                                        "value", List.of("2026-08-01", "2026-08-31")))));

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
    void downloadExportUsesCsvHeadersForCsvTempFiles() throws Exception {
        Path csv = Files.writeString(
                tempDir.resolve("cr_crawled_document_export_1.csv"),
                "Title,URL\nPump,http://127.0.0.1/item\n",
                StandardCharsets.UTF_8);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new DynamicController().downloadExport("cr_crawled_document", csv.toString(), response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(response.getHeader("Content-Disposition"))
                .contains("cr_crawled_document_export.csv");
        assertThat(response.getContentAsString()).contains("Pump");
        assertThat(Files.exists(csv)).isFalse();
    }

    @Test
    void downloadExportUsesExcelHeadersForXlsxTempFiles() throws Exception {
        Path xlsx = Files.write(
                tempDir.resolve("cr_crawled_document_export_1.xlsx"),
                new byte[] { 0x50, 0x4b, 0x03, 0x04 });
        MockHttpServletResponse response = new MockHttpServletResponse();

        new DynamicController().downloadExport("cr_crawled_document", xlsx.toString(), response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(response.getHeader("Content-Disposition"))
                .contains("cr_crawled_document_export.xlsx");
        assertThat(response.getContentAsByteArray()).containsExactly(0x50, 0x4b, 0x03, 0x04);
        assertThat(Files.exists(xlsx)).isFalse();
    }
}
