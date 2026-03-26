package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.controller.config.ModelController;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.ModelExportService;
import com.auraboot.framework.meta.service.PageSchemaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ModelController 单元测试
 * 测试新增的版本管理、批量操作、导入导出等接口
 */
@ExtendWith(MockitoExtension.class)
class ModelControllerTest {

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private PageSchemaService pageSchemaService;

    @Mock
    private ModelExportService modelExportService;

    @InjectMocks
    private ModelController controller;

    private MetaModelDTO mockModel;

    @BeforeEach
    void setUp() {
        mockModel = MetaModelDTO.builder()
                .pid("test-model-pid")
                .code("test_model")
                .displayName("测试模型")
                .version(1)
                .status("published")
                .build();
    }

    // ==================== 版本管理测试 ====================

    @Test
    void testGetVersionHistory_Success() {
        // Given
        String code = "test_model";
        List<MetaModelDTO> versions = List.of(mockModel);
        when(metaModelService.getVersionHistory(code)).thenReturn(versions);

        // When
        ApiResponse<List<MetaModelDTO>> response = controller.getVersionHistory(code);

        // Then
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals(1, response.getData().size());
        verify(metaModelService).getVersionHistory(code);
    }

    @Test
    void testGetVersionDetail_Success() {
        // Given
        String code = "test_model";
        Integer version = 1;
        when(metaModelService.getVersionDetail(code, version)).thenReturn(mockModel);

        // When
        ApiResponse<MetaModelDTO> response = controller.getVersionDetail(code, version);

        // Then
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals(code, response.getData().getCode());
        verify(metaModelService).getVersionDetail(code, version);
    }

    @Test
    void testCompareVersions_Success() {
        // Given
        String code = "test_model";
        Integer v1 = 1;
        Integer v2 = 2;
        Map<String, Object> diff = Map.of(
            "code", code,
            "v1", v1,
            "v2", v2,
            "hasChanges", false,
            "changes", List.of()
        );
        when(metaModelService.compareVersions(code, v1, v2)).thenReturn(diff);

        // When
        ApiResponse<Map<String, Object>> response = controller.compareVersions(
                code,
                Map.of("v1", v1, "v2", v2)
        );

        // Then
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals(code, response.getData().get("code"));
        assertEquals(v1, response.getData().get("v1"));
        assertEquals(v2, response.getData().get("v2"));
        verify(metaModelService).compareVersions(code, v1, v2);
    }

    @Test
    void testRollbackToVersion_Success() {
        // Given
        String code = "test_model";
        Integer version = 1;
        when(metaModelService.rollbackToVersion(code, version)).thenReturn(mockModel);

        // When
        ApiResponse<MetaModelDTO> response = controller.rollbackToVersion(
                code,
                Map.of("version", version)
        );

        // Then
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals(code, response.getData().getCode());
        verify(metaModelService).rollbackToVersion(code, version);
    }

    // ==================== 批量操作测试 ====================

    @Test
    void testBatchDelete_Success() {
        // Given
        List<String> pids = List.of("pid1", "pid2", "pid3");
        doNothing().when(metaModelService).delete(anyString());

        // When
        ApiResponse<Map<String, Object>> response = controller.batchDelete(
                Map.of("pids", pids)
        );

        // Then
        assertNotNull(response);
        assertEquals("0", response.getCode());
        Map<String, Object> result = response.getData();
        assertEquals(3, result.get("total"));
        assertEquals(3, result.get("success"));
        assertEquals(0, result.get("failure"));
        verify(metaModelService, times(3)).delete(anyString());
    }

    @Test
    void testBatchDelete_EmptyList() {
        // Given
        List<String> pids = List.of();

        // When
        ApiResponse<Map<String, Object>> response = controller.batchDelete(
                Map.of("pids", pids)
        );

        // Then
        assertNotNull(response);
        assertNotEquals("0", response.getCode());
        assertTrue(response.getMessage().contains("不能为空"));
    }

    @Test
    void testBatchDelete_PartialFailure() {
        // Given
        List<String> pids = List.of("pid1", "pid2", "pid3");
        doNothing().when(metaModelService).delete("pid1");
        doThrow(new RuntimeException("删除失败")).when(metaModelService).delete("pid2");
        doNothing().when(metaModelService).delete("pid3");

        // When
        ApiResponse<Map<String, Object>> response = controller.batchDelete(
                Map.of("pids", pids)
        );

        // Then
        assertNotNull(response);
        assertEquals("0", response.getCode());
        Map<String, Object> result = response.getData();
        assertEquals(3, result.get("total"));
        assertEquals(2, result.get("success"));
        assertEquals(1, result.get("failure"));
        @SuppressWarnings("unchecked")
        List<String> failedPids = (List<String>) result.get("failedPids");
        assertTrue(failedPids.contains("pid2"));
    }

    // ==================== 导入导出测试 ====================

    @Test
    void testExportModels_Success() {
        // Given
        List<String> modelCodes = List.of("test_model", "other_model");
        Map<String, Object> exportResult = new HashMap<>();
        exportResult.put("models", List.of(Map.of("code", "test_model")));
        exportResult.put("fields", List.of(Map.of("code", "field1")));
        exportResult.put("bindings", List.of());
        exportResult.put("commands", List.of(Map.of("code", "cmd1")));

        when(modelExportService.exportByModelCodes(modelCodes)).thenReturn(exportResult);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("modelCodes", modelCodes);

        // When
        ApiResponse<Map<String, Object>> response = controller.exportModels(requestBody);

        // Then
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertTrue(response.getData().containsKey("models"));
        assertTrue(response.getData().containsKey("fields"));
        assertTrue(response.getData().containsKey("bindings"));
        assertTrue(response.getData().containsKey("commands"));
        assertTrue(response.getData().containsKey("exportTime"));
        assertTrue(response.getData().containsKey("exportedBy"));
        verify(modelExportService).exportByModelCodes(modelCodes);
    }

    @Test
    void testExportModels_EmptyModelCodes() {
        // Given - empty modelCodes
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("modelCodes", List.of());

        // When
        ApiResponse<Map<String, Object>> response = controller.exportModels(requestBody);

        // Then
        assertNotNull(response);
        assertNotEquals("0", response.getCode());
        verify(modelExportService, never()).exportByModelCodes(any());
    }

    @Test
    void testExportModels_NullModelCodes() {
        // Given - no modelCodes key
        Map<String, Object> requestBody = new HashMap<>();

        // When
        ApiResponse<Map<String, Object>> response = controller.exportModels(requestBody);

        // Then
        assertNotNull(response);
        assertNotEquals("0", response.getCode());
        verify(modelExportService, never()).exportByModelCodes(any());
    }

    // ==================== 统计信息测试 ====================

    @Test
    void testGetStatistics_Success() {
        // Given
        Map<String, Object> statistics = Map.of(
            "totalModels", 10,
            "activeModels", 8,
            "modelsByType", Map.of("entity", 5, "view", 3, "aggregate", 2)
        );
        when(metaModelService.getStatistics()).thenReturn(statistics);

        // When
        ApiResponse<Map<String, Object>> response = controller.getStatistics();

        // Then
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals(10, response.getData().get("totalModels"));
        assertEquals(8, response.getData().get("activeModels"));
        verify(metaModelService).getStatistics();
    }

    // ==================== 数据验证测试 ====================

    @Test
    void testValidateModel_Success() {
        // Given
        Map<String, Object> modelData = Map.of("code", "test_model", "displayName", "测试模型");
        Map<String, Object> validationResult = Map.of("valid", true, "errors", Map.of());
        when(metaModelService.validateModelData(modelData)).thenReturn(validationResult);

        // When
        ApiResponse<Map<String, Object>> response = controller.validateModel(modelData);

        // Then
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertTrue((Boolean) response.getData().get("valid"));
        verify(metaModelService).validateModelData(modelData);
    }

    // ==================== Release信息测试 ====================

    @Test
    void testGetReleaseInfo_Success() {
        // Given
        String pid = "test-model-pid";
        when(metaModelService.findByPid(pid)).thenReturn(mockModel);

        // When
        ApiResponse<Map<String, Object>> response = controller.getReleaseInfo(pid);

        // Then
        assertNotNull(response);
        assertEquals("0", response.getCode());
        Map<String, Object> releaseInfo = response.getData();
        assertEquals(pid, releaseInfo.get("pid"));
        assertEquals("test_model", releaseInfo.get("code"));
        assertEquals(1, releaseInfo.get("version"));
        assertEquals("published", releaseInfo.get("status"));
    }

    @Test
    void testGetReleaseInfo_ModelNotFound() {
        // Given
        String pid = "non-existent-pid";
        when(metaModelService.findByPid(pid)).thenReturn(null);

        // When
        ApiResponse<Map<String, Object>> response = controller.getReleaseInfo(pid);

        // Then
        assertNotNull(response);
        assertNotEquals("0", response.getCode());
        assertTrue(response.getMessage().contains("不存在"));
    }

    // ==================== 关联数据查询测试 ====================

    // 注意：getModelFields 测试已移除
    // 该功能由 ModelFieldBindingController 提供
    // 请参考 ModelFieldBindingControllerTest 的测试

    @Test
    void testGetRelatedPages_Success() {
        // Given
        String pid = "test-model-pid";
        when(metaModelService.findByPid(pid)).thenReturn(mockModel);

        // When
        ApiResponse<List<PageSchemaDTO>> response = controller.getRelatedPages(pid);

        // Then
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
    }

    @Test
    void testGetRelatedPages_ModelNotFound() {
        // Given
        String pid = "non-existent-pid";
        when(metaModelService.findByPid(pid)).thenReturn(null);

        // When
        ApiResponse<List<PageSchemaDTO>> response = controller.getRelatedPages(pid);

        // Then
        assertNotNull(response);
        assertNotEquals("0", response.getCode());
        assertTrue(response.getMessage().contains("不存在"));
    }

    @Test
    void testGetModelByCode_Success() {
        // Given
        String code = "test_model";
        when(metaModelService.findByCode(code)).thenReturn(mockModel);

        // When
        ApiResponse<MetaModelDTO> response = controller.getModelByCode(code);

        // Then
        assertNotNull(response);
        assertEquals("0", response.getCode());
        assertNotNull(response.getData());
        assertEquals(code, response.getData().getCode());
        verify(metaModelService).findByCode(code);
    }
}
