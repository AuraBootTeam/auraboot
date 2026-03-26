//package com.auraboot.framework.designer.service;
//
//import com.auraboot.framework.designer.dto.*;
//import com.auraboot.framework.designer.entity.ComponentRegistry;
//import com.auraboot.framework.designer.mapper.ComponentRegistryMapper;
//import com.auraboot.framework.designer.service.impl.ComponentRegistryServiceImpl;
//import com.auraboot.framework.designer.converter.ComponentRegistryConverter;
//import com.auraboot.framework.meta.dto.PaginationRequest;
//import com.auraboot.framework.meta.dto.PaginationResult;
//import com.auraboot.framework.exception.ValidationException;
//import com.auraboot.framework.application.tenant.MetaContext;
//import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.util.Arrays;
//import java.util.List;
//import java.util.Map;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
///**
// * 组件注册服务测试类
// *
// * @author AuraBoot Framework
// * @since 1.0.0
// */
//@ExtendWith(MockitoExtension.class)
//class ComponentRegistryServiceTest {
//
//    @Mock
//    private ComponentRegistryMapper componentRegistryMapper;
//
//    @Mock
//    private ComponentRegistryConverter componentRegistryConverter;
//
//    @InjectMocks
//    private ComponentRegistryServiceImpl componentRegistryService;
//
//    private ComponentRegistry mockComponent;
//    private ComponentRegistryDTO mockComponentDTO;
//    private ComponentRegistryCreateRequest mockCreateRequest;
//    private ComponentRegistryUpdateRequest mockUpdateRequest;
//
//    @BeforeEach
//    void setUp() {
//        // 设置租户上下文
//        MetaContext.setTenantId(1L);
//
//        // 创建模拟数据
//        mockComponent = new ComponentRegistry();
//        mockComponent.setId(1L);
//        mockComponent.setPid("comp_123");
//        mockComponent.setName("TestButton");
//        mockComponent.setType("button");
//        mockComponent.setCategory("form");
//        mockComponent.setVersion("1.0.0");
//        mockComponent.setStatus("enabled");
//        mockComponent.setTenantId(1L);
//
//        mockComponentDTO = new ComponentRegistryDTO();
//        mockComponentDTO.setPid("comp_123");
//        mockComponentDTO.setName("TestButton");
//        mockComponentDTO.setType("button");
//        mockComponentDTO.setCategory("form");
//        mockComponentDTO.setVersion("1.0.0");
//        mockComponentDTO.setStatus("enabled");
//
//        mockCreateRequest = new ComponentRegistryCreateRequest();
//        mockCreateRequest.setName("TestButton");
//        mockCreateRequest.setType("button");
//        mockCreateRequest.setCategory("form");
//        mockCreateRequest.setVersion("1.0.0");
//        mockCreateRequest.setDescription("测试按钮组件");
//
//        mockUpdateRequest = new ComponentRegistryUpdateRequest();
//        mockUpdateRequest.setName("UpdatedButton");
//        mockUpdateRequest.setDescription("更新的按钮组件");
//    }
//
//    @Test
//    void testList() {
//        // 准备测试数据
//        Page<ComponentRegistry> mockPage = new Page<>(1, 10);
//        mockPage.setRecords(Arrays.asList(mockComponent));
//        mockPage.setTotal(1);
//
//        PaginationRequest paginationRequest = new PaginationRequest();
//        paginationRequest.setPage(1);
//        paginationRequest.setSize(10);
//
//        // 设置模拟行为
//        when(componentRegistryMapper.selectPage(any(Page.class), anyString(), anyString(),
//            anyString(), anyString(), anyLong())).thenReturn(mockPage);
//        when(componentRegistryConverter.toDTO(mockComponent)).thenReturn(mockComponentDTO);
//
//        // 执行测试
//        PaginationResult<ComponentRegistryDTO> result = componentRegistryService.list(
//            "TestButton", "button", "form", "enabled", paginationRequest);
//
//        // 验证结果
//        assertNotNull(result);
//        assertEquals(1, result.getTotalElements());
//        assertEquals(1, result.getContent().size());
//        assertEquals("TestButton", result.getContent().get(0).getName());
//
//        // 验证方法调用
//        verify(componentRegistryMapper).selectPage(any(Page.class), eq("TestButton"),
//            eq("button"), eq("form"), eq("enabled"), eq(1L));
//        verify(componentRegistryConverter).toDTO(mockComponent);
//    }
//
//    @Test
//    void testFindByPid() {
//        // 设置模拟行为
//        when(componentRegistryMapper.findByPid("comp_123")).thenReturn(mockComponent);
//        when(componentRegistryConverter.toDTO(mockComponent)).thenReturn(mockComponentDTO);
//
//        // 执行测试
//        ComponentRegistryDTO result = componentRegistryService.findByPid("comp_123");
//
//        // 验证结果
//        assertNotNull(result);
//        assertEquals("comp_123", result.getPid());
//        assertEquals("TestButton", result.getName());
//
//        // 验证方法调用
//        verify(componentRegistryMapper).findByPid("comp_123");
//        verify(componentRegistryConverter).toDTO(mockComponent);
//    }
//
//    @Test
//    void testFindByPid_NotFound() {
//        // 设置模拟行为
//        when(componentRegistryMapper.findByPid("nonexistent")).thenReturn(null);
//
//        // 执行测试并验证异常
//        ValidationException exception = assertThrows(ValidationException.class,
//            () -> componentRegistryService.findByPid("nonexistent"));
//
//        assertEquals("组件不存在", exception.getMessage());
//        verify(componentRegistryMapper).findByPid("nonexistent");
//    }
//
//    @Test
//    void testFindByPid_EmptyPid() {
//        // 执行测试并验证异常
//        ValidationException exception = assertThrows(ValidationException.class,
//            () -> componentRegistryService.findByPid(""));
//
//        assertEquals("组件PID不能为空", exception.getMessage());
//    }
//
//    @Test
//    void testCreate() {
//        // 设置模拟行为
//        when(componentRegistryMapper.existsByName("TestButton")).thenReturn(false);
//        when(componentRegistryConverter.toEntity(mockCreateRequest)).thenReturn(mockComponent);
//        when(componentRegistryMapper.insert(mockComponent)).thenReturn(1);
//        when(componentRegistryConverter.toDTO(mockComponent)).thenReturn(mockComponentDTO);
//
//        // 执行测试
//        ComponentRegistryDTO result = componentRegistryService.create(mockCreateRequest);
//
//        // 验证结果
//        assertNotNull(result);
//        assertEquals("TestButton", result.getName());
//
//        // 验证方法调用
//        verify(componentRegistryMapper).existsByName("TestButton");
//        verify(componentRegistryConverter).toEntity(mockCreateRequest);
//        verify(componentRegistryMapper).insert(mockComponent);
//        verify(componentRegistryConverter).toDTO(mockComponent);
//    }
//
//    @Test
//    void testCreate_DuplicateName() {
//        // 设置模拟行为
//        when(componentRegistryMapper.existsByName("TestButton")).thenReturn(true);
//
//        // 执行测试并验证异常
//        ValidationException exception = assertThrows(ValidationException.class,
//            () -> componentRegistryService.create(mockCreateRequest));
//
//        assertEquals("组件名称已存在", exception.getMessage());
//        verify(componentRegistryMapper).existsByName("TestButton");
//    }
//
//    @Test
//    void testCreate_NullRequest() {
//        // 执行测试并验证异常
//        ValidationException exception = assertThrows(ValidationException.class,
//            () -> componentRegistryService.create(null));
//
//        assertEquals("创建请求不能为空", exception.getMessage());
//    }
//
//    @Test
//    void testUpdate() {
//        // 设置模拟行为
//        when(componentRegistryMapper.findByPid("comp_123")).thenReturn(mockComponent);
//        when(componentRegistryMapper.existsByName("UpdatedButton")).thenReturn(false);
//        when(componentRegistryMapper.updateById(mockComponent)).thenReturn(1);
//        when(componentRegistryConverter.toDTO(mockComponent)).thenReturn(mockComponentDTO);
//
//        // 执行测试
//        ComponentRegistryDTO result = componentRegistryService.update("comp_123", mockUpdateRequest);
//
//        // 验证结果
//        assertNotNull(result);
//
//        // 验证方法调用
//        verify(componentRegistryMapper).findByPid("comp_123");
//        verify(componentRegistryConverter).updateEntity(mockComponent, mockUpdateRequest);
//        verify(componentRegistryMapper).updateById(mockComponent);
//        verify(componentRegistryConverter).toDTO(mockComponent);
//    }
//
//    @Test
//    void testDelete() {
//        // 设置模拟行为
//        when(componentRegistryMapper.findByPid("comp_123")).thenReturn(mockComponent);
//        when(componentRegistryMapper.deleteById(1L)).thenReturn(1);
//
//        // 执行测试
//        assertDoesNotThrow(() -> componentRegistryService.delete("comp_123"));
//
//        // 验证方法调用
//        verify(componentRegistryMapper).findByPid("comp_123");
//        verify(componentRegistryMapper).deleteById(1L);
//    }
//
//    @Test
//    void testBatchDelete() {
//        List<String> pids = Arrays.asList("comp_123", "comp_456");
//
//        // 设置模拟行为
//        when(componentRegistryMapper.findByPid("comp_123")).thenReturn(mockComponent);
//        when(componentRegistryMapper.findByPid("comp_456")).thenReturn(mockComponent);
//        when(componentRegistryMapper.deleteById(1L)).thenReturn(1);
//
//        // 执行测试
//        assertDoesNotThrow(() -> componentRegistryService.batchDelete(pids));
//
//        // 验证方法调用
//        verify(componentRegistryMapper, times(2)).findByPid(anyString());
//        verify(componentRegistryMapper, times(2)).deleteById(1L);
//    }
//
//    @Test
//    void testEnable() {
//        // 设置模拟行为
//        when(componentRegistryMapper.findByPid("comp_123")).thenReturn(mockComponent);
//        when(componentRegistryMapper.updateById(mockComponent)).thenReturn(1);
//
//        // 执行测试
//        assertDoesNotThrow(() -> componentRegistryService.enable("comp_123"));
//
//        // 验证状态更新
//        assertEquals("enabled", mockComponent.getStatus());
//
//        // 验证方法调用
//        verify(componentRegistryMapper).findByPid("comp_123");
//        verify(componentRegistryMapper).updateById(mockComponent);
//    }
//
//    @Test
//    void testDisable() {
//        // 设置模拟行为
//        when(componentRegistryMapper.findByPid("comp_123")).thenReturn(mockComponent);
//        when(componentRegistryMapper.updateById(mockComponent)).thenReturn(1);
//
//        // 执行测试
//        assertDoesNotThrow(() -> componentRegistryService.disable("comp_123"));
//
//        // 验证状态更新
//        assertEquals("disabled", mockComponent.getStatus());
//
//        // 验证方法调用
//        verify(componentRegistryMapper).findByPid("comp_123");
//        verify(componentRegistryMapper).updateById(mockComponent);
//    }
//
//    @Test
//    void testFindByName() {
//        // 设置模拟行为
//        when(componentRegistryMapper.findByName("TestButton")).thenReturn(mockComponent);
//        when(componentRegistryConverter.toDTO(mockComponent)).thenReturn(mockComponentDTO);
//
//        // 执行测试
//        ComponentRegistryDTO result = componentRegistryService.findByName("TestButton");
//
//        // 验证结果
//        assertNotNull(result);
//        assertEquals("TestButton", result.getName());
//
//        // 验证方法调用
//        verify(componentRegistryMapper).findByName("TestButton");
//        verify(componentRegistryConverter).toDTO(mockComponent);
//    }
//
//    @Test
//    void testFindByType() {
//        List<ComponentRegistry> components = Arrays.asList(mockComponent);
//
//        // 设置模拟行为
//        when(componentRegistryMapper.findByType("button")).thenReturn(components);
//        when(componentRegistryConverter.toDTO(mockComponent)).thenReturn(mockComponentDTO);
//
//        // 执行测试
//        List<ComponentRegistryDTO> result = componentRegistryService.findByType("button");
//
//        // 验证结果
//        assertNotNull(result);
//        assertEquals(1, result.size());
//        assertEquals("button", result.get(0).getType());
//
//        // 验证方法调用
//        verify(componentRegistryMapper).findByType("button");
//        verify(componentRegistryConverter).toDTO(mockComponent);
//    }
//
//    @Test
//    void testCount() {
//        // 设置模拟行为
//        when(componentRegistryMapper.countTotal(1L)).thenReturn(10L);
//
//        // 执行测试
//        long result = componentRegistryService.count();
//
//        // 验证结果
//        assertEquals(10L, result);
//
//        // 验证方法调用
//        verify(componentRegistryMapper).countTotal(1L);
//    }
//
//    @Test
//    void testCountByStatus() {
//        // 设置模拟行为
//        when(componentRegistryMapper.countByStatus("enabled", 1L)).thenReturn(8L);
//
//        // 执行测试
//        long result = componentRegistryService.countByStatus("enabled");
//
//        // 验证结果
//        assertEquals(8L, result);
//
//        // 验证方法调用
//        verify(componentRegistryMapper).countByStatus("enabled", 1L);
//    }
//
//    @Test
//    void testExistsByName() {
//        // 设置模拟行为
//        when(componentRegistryMapper.existsByName("TestButton")).thenReturn(true);
//
//        // 执行测试
//        boolean result = componentRegistryService.existsByName("TestButton");
//
//        // 验证结果
//        assertTrue(result);
//
//        // 验证方法调用
//        verify(componentRegistryMapper).existsByName("TestButton");
//    }
//
//    @Test
//    void testIsNameUnique_WithoutExclude() {
//        // 设置模拟行为
//        when(componentRegistryMapper.existsByName("NewButton")).thenReturn(false);
//
//        // 执行测试
//        boolean result = componentRegistryService.isNameUnique("NewButton", null);
//
//        // 验证结果
//        assertTrue(result);
//
//        // 验证方法调用
//        verify(componentRegistryMapper).existsByName("NewButton");
//    }
//
//    @Test
//    void testIsNameUnique_WithExclude() {
//        // 设置模拟行为
//        when(componentRegistryMapper.findByName("TestButton")).thenReturn(mockComponent);
//
//        // 执行测试
//        boolean result = componentRegistryService.isNameUnique("TestButton", "comp_123");
//
//        // 验证结果
//        assertTrue(result);
//
//        // 验证方法调用
//        verify(componentRegistryMapper).findByName("TestButton");
//    }
//
//    @Test
//    void testGetStatistics() {
//        // 设置模拟行为
//        when(componentRegistryMapper.countTotal(1L)).thenReturn(10L);
//        when(componentRegistryMapper.countByStatus("enabled", 1L)).thenReturn(8L);
//        when(componentRegistryMapper.countByStatus("disabled", 1L)).thenReturn(2L);
//        when(componentRegistryMapper.getTypeStats(1L)).thenReturn(Arrays.asList(
//            Map.of("type", "button", "count", 5),
//            Map.of("type", "input", "count", 3)
//        ));
//
//        // 执行测试
//        Map<String, Object> result = componentRegistryService.getStatistics();
//
//        // 验证结果
//        assertNotNull(result);
//        assertEquals(10L, result.get("totalCount"));
//        assertEquals(8L, result.get("enabledCount"));
//        assertEquals(2L, result.get("disabledCount"));
//        assertNotNull(result.get("typeStats"));
//
//        // 验证方法调用
//        verify(componentRegistryMapper).countTotal(1L);
//        verify(componentRegistryMapper).countByStatus("enabled", 1L);
//        verify(componentRegistryMapper).countByStatus("disabled", 1L);
//        verify(componentRegistryMapper).getTypeStats(1L);
//    }
//}