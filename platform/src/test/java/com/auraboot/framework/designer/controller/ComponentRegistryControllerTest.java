//package com.auraboot.framework.designer.controller;
//
//import com.auraboot.framework.designer.dto.*;
//import com.auraboot.framework.designer.service.ComponentRegistryService;
//import com.auraboot.framework.meta.dto.PaginationRequest;
//import com.auraboot.framework.meta.dto.PaginationResult;
//import com.auraboot.framework.common.dto.ApiResponse;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
//import org.springframework.boot.test.mock.mockito.MockBean;
//import org.springframework.http.MediaType;
//import org.springframework.test.web.servlet.MockMvc;
//
//import java.util.Arrays;
//import java.util.List;
//import java.util.Map;
//
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
//
///**
// * 组件注册控制器测试类
// *
// * @author AuraBoot Framework
// * @since 1.0.0
// */
//@ExtendWith(MockitoExtension.class)
//@WebMvcTest(ComponentRegistryController.class)
//class ComponentRegistryControllerTest {
//
//    @Autowired
//    private MockMvc mockMvc;
//
//    @MockBean
//    private ComponentRegistryService componentRegistryService;
//
//    @Autowired
//    private ObjectMapper objectMapper;
//
//    private ComponentRegistryDTO mockComponentDTO;
//    private ComponentRegistryCreateRequest mockCreateRequest;
//    private ComponentRegistryUpdateRequest mockUpdateRequest;
//
//    @BeforeEach
//    void setUp() {
//        mockComponentDTO = new ComponentRegistryDTO();
//        mockComponentDTO.setPid("comp_123");
//        mockComponentDTO.setName("测试组件");
//        mockComponentDTO.setType("form");
//        mockComponentDTO.setCategory("input");
//        mockComponentDTO.setStatus("enabled");
//
//        mockCreateRequest = new ComponentRegistryCreateRequest();
//        mockCreateRequest.setName("测试组件");
//        mockCreateRequest.setType("form");
//        mockCreateRequest.setCategory("input");
//        mockCreateRequest.setStatus("enabled");
//
//        mockUpdateRequest = new ComponentRegistryUpdateRequest();
//        mockUpdateRequest.setName("更新的组件");
//        mockUpdateRequest.setStatus("disabled");
//    }
//
//    @Test
//    void testList() throws Exception {
//        // 准备测试数据
//        PaginationResult<ComponentRegistryDTO> paginationResult = new PaginationResult<>();
//        paginationResult.setContent(Arrays.asList(mockComponentDTO));
//        paginationResult.setTotalElements(1);
//        paginationResult.setTotalPages(1);
//        paginationResult.setPage(1);
//        paginationResult.setSize(10);
//
//        // 设置模拟行为
//        when(componentRegistryService.list(any(PaginationRequest.class))).thenReturn(paginationResult);
//
//        // 执行测试
//        mockMvc.perform(get("/api/designer/components")
//                .param("page", "1")
//                .param("size", "10")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data.content[0].pid").value("comp_123"))
//                .andExpect(jsonPath("$.data.content[0].name").value("测试组件"));
//
//        // 验证方法调用
//        verify(componentRegistryService).list(any(PaginationRequest.class));
//    }
//
//    @Test
//    void testFindByPid() throws Exception {
//        // 设置模拟行为
//        when(componentRegistryService.findByPid("comp_123")).thenReturn(mockComponentDTO);
//
//        // 执行测试
//        mockMvc.perform(get("/api/designer/components/comp_123")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data.pid").value("comp_123"))
//                .andExpect(jsonPath("$.data.name").value("测试组件"));
//
//        // 验证方法调用
//        verify(componentRegistryService).findByPid("comp_123");
//    }
//
//    @Test
//    void testCreate() throws Exception {
//        // 设置模拟行为
//        when(componentRegistryService.create(any(ComponentRegistryCreateRequest.class))).thenReturn(mockComponentDTO);
//
//        // 执行测试
//        mockMvc.perform(post("/api/designer/components")
//                .contentType(MediaType.APPLICATION_JSON)
//                .content(objectMapper.writeValueAsString(mockCreateRequest)))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data.pid").value("comp_123"))
//                .andExpect(jsonPath("$.data.name").value("测试组件"));
//
//        // 验证方法调用
//        verify(componentRegistryService).create(any(ComponentRegistryCreateRequest.class));
//    }
//
//    @Test
//    void testUpdate() throws Exception {
//        // 设置模拟行为
//        when(componentRegistryService.update(eq("comp_123"), any(ComponentRegistryUpdateRequest.class)))
//                .thenReturn(mockComponentDTO);
//
//        // 执行测试
//        mockMvc.perform(put("/api/designer/components/comp_123")
//                .contentType(MediaType.APPLICATION_JSON)
//                .content(objectMapper.writeValueAsString(mockUpdateRequest)))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data.pid").value("comp_123"));
//
//        // 验证方法调用
//        verify(componentRegistryService).update(eq("comp_123"), any(ComponentRegistryUpdateRequest.class));
//    }
//
//    @Test
//    void testDelete() throws Exception {
//        // 设置模拟行为
//        doNothing().when(componentRegistryService).delete("comp_123");
//
//        // 执行测试
//        mockMvc.perform(delete("/api/designer/components/comp_123")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true));
//
//        // 验证方法调用
//        verify(componentRegistryService).delete("comp_123");
//    }
//
//    @Test
//    void testBatchDelete() throws Exception {
//        List<String> pids = Arrays.asList("comp_123", "comp_456");
//
//        // 设置模拟行为
//        doNothing().when(componentRegistryService).batchDelete(pids);
//
//        // 执行测试
//        mockMvc.perform(delete("/api/designer/components/batch")
//                .contentType(MediaType.APPLICATION_JSON)
//                .content(objectMapper.writeValueAsString(pids)))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true));
//
//        // 验证方法调用
//        verify(componentRegistryService).batchDelete(pids);
//    }
//
//    @Test
//    void testEnable() throws Exception {
//        // 设置模拟行为
//        doNothing().when(componentRegistryService).enable("comp_123");
//
//        // 执行测试
//        mockMvc.perform(put("/api/designer/components/comp_123/enable")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true));
//
//        // 验证方法调用
//        verify(componentRegistryService).enable("comp_123");
//    }
//
//    @Test
//    void testDisable() throws Exception {
//        // 设置模拟行为
//        doNothing().when(componentRegistryService).disable("comp_123");
//
//        // 执行测试
//        mockMvc.perform(put("/api/designer/components/comp_123/disable")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true));
//
//        // 验证方法调用
//        verify(componentRegistryService).disable("comp_123");
//    }
//
//    @Test
//    void testFindByName() throws Exception {
//        // 设置模拟行为
//        when(componentRegistryService.findByName("测试组件")).thenReturn(mockComponentDTO);
//
//        // 执行测试
//        mockMvc.perform(get("/api/designer/components/name/测试组件")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data.name").value("测试组件"));
//
//        // 验证方法调用
//        verify(componentRegistryService).findByName("测试组件");
//    }
//
//    @Test
//    void testFindByType() throws Exception {
//        List<ComponentRegistryDTO> components = Arrays.asList(mockComponentDTO);
//
//        // 设置模拟行为
//        when(componentRegistryService.findByType("form")).thenReturn(components);
//
//        // 执行测试
//        mockMvc.perform(get("/api/designer/components/type/FORM")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data[0].type").value("form"));
//
//        // 验证方法调用
//        verify(componentRegistryService).findByType("form");
//    }
//
//    @Test
//    void testFindByCategory() throws Exception {
//        List<ComponentRegistryDTO> components = Arrays.asList(mockComponentDTO);
//
//        // 设置模拟行为
//        when(componentRegistryService.findByCategory("input")).thenReturn(components);
//
//        // 执行测试
//        mockMvc.perform(get("/api/designer/components/category/INPUT")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data[0].category").value("input"));
//
//        // 验证方法调用
//        verify(componentRegistryService).findByCategory("input");
//    }
//
//    @Test
//    void testFindByStatus() throws Exception {
//        List<ComponentRegistryDTO> components = Arrays.asList(mockComponentDTO);
//
//        // 设置模拟行为
//        when(componentRegistryService.findByStatus("enabled")).thenReturn(components);
//
//        // 执行测试
//        mockMvc.perform(get("/api/designer/components/status/ENABLED")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data[0].status").value("enabled"));
//
//        // 验证方法调用
//        verify(componentRegistryService).findByStatus("enabled");
//    }
//
//    @Test
//    void testGetCategories() throws Exception {
//        List<String> categories = Arrays.asList("input", "display", "layout");
//
//        // 设置模拟行为
//        when(componentRegistryService.getCategories()).thenReturn(categories);
//
//        // 执行测试
//        mockMvc.perform(get("/api/designer/components/categories")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data[0]").value("input"));
//
//        // 验证方法调用
//        verify(componentRegistryService).getCategories();
//    }
//
//    @Test
//    void testGetTypes() throws Exception {
//        List<String> types = Arrays.asList("form", "table", "chart");
//
//        // 设置模拟行为
//        when(componentRegistryService.getTypes()).thenReturn(types);
//
//        // 执行测试
//        mockMvc.perform(get("/api/designer/components/types")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data[0]").value("form"));
//
//        // 验证方法调用
//        verify(componentRegistryService).getTypes();
//    }
//
//    @Test
//    void testGetTags() throws Exception {
//        List<String> tags = Arrays.asList("常用", "高级", "实验性");
//
//        // 设置模拟行为
//        when(componentRegistryService.getTags()).thenReturn(tags);
//
//        // 执行测试
//        mockMvc.perform(get("/api/designer/components/tags")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data[0]").value("常用"));
//
//        // 验证方法调用
//        verify(componentRegistryService).getTags();
//    }
//
//    @Test
//    void testCount() throws Exception {
//        // 设置模拟行为
//        when(componentRegistryService.count()).thenReturn(10L);
//
//        // 执行测试
//        mockMvc.perform(get("/api/designer/components/count")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpected(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value(10));
//
//        // 验证方法调用
//        verify(componentRegistryService).count();
//    }
//
//    @Test
//    void testCountByStatus() throws Exception {
//        // 设置模拟行为
//        when(componentRegistryService.countByStatus("enabled")).thenReturn(8L);
//
//        // 执行测试
//        mockMvc.perform(get("/api/designer/components/count/status/ENABLED")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value(8));
//
//        // 验证方法调用
//        verify(componentRegistryService).countByStatus("enabled");
//    }
//
//    @Test
//    void testExistsByName() throws Exception {
//        // 设置模拟行为
//        when(componentRegistryService.existsByName("测试组件")).thenReturn(true);
//
//        // 执行测试
//        mockMvc.perform(get("/api/designer/components/exists/测试组件")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value(true));
//
//        // 验证方法调用
//        verify(componentRegistryService).existsByName("测试组件");
//    }
//
//    @Test
//    void testIsNameUnique() throws Exception {
//        // 设置模拟行为
//        when(componentRegistryService.isNameUnique("新组件")).thenReturn(true);
//
//        // 执行测试
//        mockMvc.perform(get("/api/designer/components/unique/新组件")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data").value(true));
//
//        // 验证方法调用
//        verify(componentRegistryService).isNameUnique("新组件");
//    }
//
//    @Test
//    void testUpdateSortWeight() throws Exception {
//        // 设置模拟行为
//        doNothing().when(componentRegistryService).updateSortWeight("comp_123", 5);
//
//        // 执行测试
//        mockMvc.perform(put("/api/designer/components/comp_123/sort-weight")
//                .param("weight", "5")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true));
//
//        // 验证方法调用
//        verify(componentRegistryService).updateSortWeight("comp_123", 5);
//    }
//
//    @Test
//    void testCopy() throws Exception {
//        // 设置模拟行为
//        when(componentRegistryService.copy("comp_123", "测试组件_副本")).thenReturn(mockComponentDTO);
//
//        // 执行测试
//        mockMvc.perform(post("/api/designer/components/comp_123/copy")
//                .param("newName", "测试组件_副本")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data.pid").value("comp_123"));
//
//        // 验证方法调用
//        verify(componentRegistryService).copy("comp_123", "测试组件_副本");
//    }
//
//    @Test
//    void testImportComponents() throws Exception {
//        List<ComponentRegistryCreateRequest> requests = Arrays.asList(mockCreateRequest);
//        List<ComponentRegistryDTO> results = Arrays.asList(mockComponentDTO);
//
//        // 设置模拟行为
//        when(componentRegistryService.importComponents(requests)).thenReturn(results);
//
//        // 执行测试
//        mockMvc.perform(post("/api/designer/components/import")
//                .contentType(MediaType.APPLICATION_JSON)
//                .content(objectMapper.writeValueAsString(requests)))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data[0].pid").value("comp_123"));
//
//        // 验证方法调用
//        verify(componentRegistryService).importComponents(requests);
//    }
//
//    @Test
//    void testExportComponents() throws Exception {
//        List<String> pids = Arrays.asList("comp_123", "comp_456");
//        List<ComponentRegistryDTO> results = Arrays.asList(mockComponentDTO);
//
//        // 设置模拟行为
//        when(componentRegistryService.exportComponents(pids)).thenReturn(results);
//
//        // 执行测试
//        mockMvc.perform(post("/api/designer/components/export")
//                .contentType(MediaType.APPLICATION_JSON)
//                .content(objectMapper.writeValueAsString(pids)))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data[0].pid").value("comp_123"));
//
//        // 验证方法调用
//        verify(componentRegistryService).exportComponents(pids);
//    }
//
//    @Test
//    void testGetStatistics() throws Exception {
//        Map<String, Object> statistics = Map.of(
//            "totalCount", 10L,
//            "enabledCount", 8L,
//            "disabledCount", 2L
//        );
//
//        // 设置模拟行为
//        when(componentRegistryService.getStatistics()).thenReturn(statistics);
//
//        // 执行测试
//        mockMvc.perform(get("/api/designer/components/statistics")
//                .contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.success").value(true))
//                .andExpect(jsonPath("$.data.totalCount").value(10))
//                .andExpect(jsonPath("$.data.enabledCount").value(8));
//
//        // 验证方法调用
//        verify(componentRegistryService).getStatistics();
//    }
//}