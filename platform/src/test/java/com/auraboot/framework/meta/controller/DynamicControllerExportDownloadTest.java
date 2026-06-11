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
