//package com.auraboot.framework.designer.service;
//
//import com.auraboot.framework.designer.dto.*;
//import com.auraboot.framework.designer.entity.RoutePolicy;
//import com.auraboot.framework.designer.mapper.RoutePolicyMapper;
//import com.auraboot.framework.designer.service.impl.RoutePolicyServiceImpl;
//import com.auraboot.framework.designer.converter.RoutePolicyConverter;
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
//import java.time.LocalDateTime;
//import java.util.Arrays;
//import java.util.List;
//import java.util.Map;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
///**
// * 路由策略服务测试类
// *
// * @author AuraBoot Framework
// * @since 1.0.0
// */
//@ExtendWith(MockitoExtension.class)
//class RoutePolicyServiceTest {
//
//    @Mock
//    private RoutePolicyMapper routePolicyMapper;
//
//    @Mock
//    private RoutePolicyConverter routePolicyConverter;
//
//    @InjectMocks
//    private RoutePolicyServiceImpl routePolicyService;
//
//    private RoutePolicy mockPolicy;
//    private RoutePolicyDTO mockPolicyDTO;
//    private RoutePolicyCreateRequest mockCreateRequest;
//    private RoutePolicyUpdateRequest mockUpdateRequest;
//
//    @BeforeEach
//    void setUp() {
//        // 设置租户上下文
//        MetaContext.setTenantId(1L);
//
//        // 创建模拟数据
//        mockPolicy = new RoutePolicy();
//        mockPolicy.setId(1L);
//        mockPolicy.setPid("route_123");
//        mockPolicy.setName("测试路由策略");
//        mockPolicy.setRouteType("page");
//        mockPolicy.setStatus("enabled");
//        mockPolicy.setPriority(100);
//        mockPolicy.setSortWeight(1);
//        mockPolicy.setTenantId(1L);
//        mockPolicy.setCreatedAt(LocalDateTime.now());
//
//        mockPolicyDTO = new RoutePolicyDTO();
//        mockPolicyDTO.setPid("route_123");
//        mockPolicyDTO.setName("测试路由策略");
//        mockPolicyDTO.setRouteType("page");
//        mockPolicyDTO.setStatus("enabled");
//        mockPolicyDTO.setPriority(100);
//        mockPolicyDTO.setSortWeight(1);
//
//        mockCreateRequest = new RoutePolicyCreateRequest();
//        mockCreateRequest.setName("测试路由策略");
//        mockCreateRequest.setRouteType("page");
//        mockCreateRequest.setStatus("enabled");
//        mockCreateRequest.setPriority(100);
//
//        mockUpdateRequest = new RoutePolicyUpdateRequest();
//        mockUpdateRequest.setName("更新的路由策略");
//        mockUpdateRequest.setStatus("disabled");
//        mockUpdateRequest.setPriority(200);
//    }
//
//    @Test
//    void testList() {
//        // 准备测试数据
//        Page<RoutePolicy> mockPage = new Page<>(1, 10);
//        mockPage.setRecords(Arrays.asList(mockPolicy));
//        mockPage.setTotal(1);
//
//        PaginationRequest paginationRequest = new PaginationRequest();
//        paginationRequest.setPage(1);
//        paginationRequest.setSize(10);
//
//        // 设置模拟行为
//        when(routePolicyMapper.selectPage(any(Page.class), anyString(), anyString(),
//            anyString(), anyLong())).thenReturn(mockPage);
//        when(routePolicyConverter.toDTO(mockPolicy)).thenReturn(mockPolicyDTO);
//
//        // 执行测试
//        PaginationResult<RoutePolicyDTO> result = routePolicyService.list(
//            "page", "enabled", "测试", paginationRequest);
//
//        // 验证结果
//        assertNotNull(result);
//        assertEquals(1, result.getTotalElements());
//        assertEquals(1, result.getContent().size());
//        assertEquals("route_123", result.getContent().get(0).getPid());
//
//        // 验证方法调用
//        verify(routePolicyMapper).selectPage(any(Page.class), eq("page"),
//            eq("enabled"), eq("测试"), eq(1L));
//        verify(routePolicyConverter).toDTO(mockPolicy);
//    }
//
//    @Test
//    void testFindByPid() {
//        // 设置模拟行为
//        when(routePolicyMapper.findByPid("route_123")).thenReturn(mockPolicy);
//        when(routePolicyConverter.toDTO(mockPolicy)).thenReturn(mockPolicyDTO);
//
//        // 执行测试
//        RoutePolicyDTO result = routePolicyService.findByPid("route_123");
//
//        // 验证结果
//        assertNotNull(result);
//        assertEquals("route_123", result.getPid());
//        assertEquals("测试路由策略", result.getName());
//
//        // 验证方法调用
//        verify(routePolicyMapper).findByPid("route_123");
//        verify(routePolicyConverter).toDTO(mockPolicy);
//    }
//
//    @Test
//    void testFindByPid_NotFound() {
//        // 设置模拟行为
//        when(routePolicyMapper.findByPid("nonexistent")).thenReturn(null);
//
//        // 执行测试并验证异常
//        ValidationException exception = assertThrows(ValidationException.class,
//            () -> routePolicyService.findByPid("nonexistent"));
//
//        assertEquals("路由策略不存在", exception.getMessage());
//        verify(routePolicyMapper).findByPid("nonexistent");
//    }
//
//    @Test
//    void testFindByPid_EmptyPid() {
//        // 执行测试并验证异常
//        ValidationException exception = assertThrows(ValidationException.class,
//            () -> routePolicyService.findByPid(""));
//
//        assertEquals("路由策略PID不能为空", exception.getMessage());
//    }
//
//    @Test
//    void testFindByName() {
//        // 设置模拟行为
//        when(routePolicyMapper.findByName("测试路由策略")).thenReturn(mockPolicy);
//        when(routePolicyConverter.toDTO(mockPolicy)).thenReturn(mockPolicyDTO);
//
//        // 执行测试
//        RoutePolicyDTO result = routePolicyService.findByName("测试路由策略");
//
//        // 验证结果
//        assertNotNull(result);
//        assertEquals("测试路由策略", result.getName());
//
//        // 验证方法调用
//        verify(routePolicyMapper).findByName("测试路由策略");
//        verify(routePolicyConverter).toDTO(mockPolicy);
//    }
//
//    @Test
//    void testCreate() {
//        // 设置模拟行为
//        when(routePolicyMapper.existsByName("测试路由策略")).thenReturn(false);
//        when(routePolicyConverter.toEntity(mockCreateRequest)).thenReturn(mockPolicy);
//        when(routePolicyMapper.insert(mockPolicy)).thenReturn(1);
//        when(routePolicyConverter.toDTO(mockPolicy)).thenReturn(mockPolicyDTO);
//
//        // 执行测试
//        RoutePolicyDTO result = routePolicyService.create(mockCreateRequest);
//
//        // 验证结果
//        assertNotNull(result);
//        assertEquals("测试路由策略", result.getName());
//
//        // 验证方法调用
//        verify(routePolicyMapper).existsByName("测试路由策略");
//        verify(routePolicyConverter).toEntity(mockCreateRequest);
//        verify(routePolicyMapper).insert(mockPolicy);
//        verify(routePolicyConverter).toDTO(mockPolicy);
//    }
//
//    @Test
//    void testCreate_NullRequest() {
//        // 执行测试并验证异常
//        ValidationException exception = assertThrows(ValidationException.class,
//            () -> routePolicyService.create(null));
//
//        assertEquals("创建请求不能为空", exception.getMessage());
//    }
//
//    @Test
//    void testCreate_DuplicateName() {
//        // 设置模拟行为
//        when(routePolicyMapper.existsByName("测试路由策略")).thenReturn(true);
//
//        // 执行测试并验证异常
//        ValidationException exception = assertThrows(ValidationException.class,
//            () -> routePolicyService.create(mockCreateRequest));
//
//        assertEquals("路由策略名称已存在", exception.getMessage());
//        verify(routePolicyMapper).existsByName("测试路由策略");
//    }
//
//    @Test
//    void testUpdate() {
//        // 设置模拟行为
//        when(routePolicyMapper.findByPid("route_123")).thenReturn(mockPolicy);
//        when(routePolicyMapper.existsByNameAndNotPid("更新的路由策略", "route_123")).thenReturn(false);
//        when(routePolicyMapper.updateById(mockPolicy)).thenReturn(1);
//        when(routePolicyConverter.toDTO(mockPolicy)).thenReturn(mockPolicyDTO);
//
//        // 执行测试
//        RoutePolicyDTO result = routePolicyService.update("route_123", mockUpdateRequest);
//
//        // 验证结果
//        assertNotNull(result);
//
//        // 验证方法调用
//        verify(routePolicyMapper).findByPid("route_123");
//        verify(routePolicyMapper).existsByNameAndNotPid("更新的路由策略", "route_123");
//        verify(routePolicyConverter).updateEntity(mockPolicy, mockUpdateRequest);
//        verify(routePolicyMapper).updateById(mockPolicy);
//        verify(routePolicyConverter).toDTO(mockPolicy);
//    }
//
//    @Test
//    void testUpdate_DuplicateName() {
//        // 设置模拟行为
//        when(routePolicyMapper.findByPid("route_123")).thenReturn(mockPolicy);
//        when(routePolicyMapper.existsByNameAndNotPid("更新的路由策略", "route_123")).thenReturn(true);
//
//        // 执行测试并验证异常
//        ValidationException exception = assertThrows(ValidationException.class,
//            () -> routePolicyService.update("route_123", mockUpdateRequest));
//
//        assertEquals("路由策略名称已存在", exception.getMessage());
//        verify(routePolicyMapper).findByPid("route_123");
//        verify(routePolicyMapper).existsByNameAndNotPid("更新的路由策略", "route_123");
//    }
//
//    @Test
//    void testDelete() {
//        // 设置模拟行为
//        when(routePolicyMapper.findByPid("route_123")).thenReturn(mockPolicy);
//        when(routePolicyMapper.deleteById(1L)).thenReturn(1);
//
//        // 执行测试
//        assertDoesNotThrow(() -> routePolicyService.delete("route_123"));
//
//        // 验证方法调用
//        verify(routePolicyMapper).findByPid("route_123");
//        verify(routePolicyMapper).deleteById(1L);
//    }
//
//    @Test
//    void testBatchDelete() {
//        List<String> pids = Arrays.asList("route_123", "route_456");
//
//        // 设置模拟行为
//        when(routePolicyMapper.findByPid("route_123")).thenReturn(mockPolicy);
//        when(routePolicyMapper.findByPid("route_456")).thenReturn(mockPolicy);
//        when(routePolicyMapper.deleteById(1L)).thenReturn(1);
//
//        // 执行测试
//        assertDoesNotThrow(() -> routePolicyService.batchDelete(pids));
//
//        // 验证方法调用
//        verify(routePolicyMapper, times(2)).findByPid(anyString());
//        verify(routePolicyMapper, times(2)).deleteById(1L);
//    }
//
//    @Test
//    void testEnable() {
//        mockPolicy.setStatus("disabled");
//
//        // 设置模拟行为
//        when(routePolicyMapper.findByPid("route_123")).thenReturn(mockPolicy);
//        when(routePolicyMapper.updateById(mockPolicy)).thenReturn(1);
//
//        // 执行测试
//        assertDoesNotThrow(() -> routePolicyService.enable("route_123"));
//
//        // 验证状态更新
//        assertEquals("enabled", mockPolicy.getStatus());
//
//        // 验证方法调用
//        verify(routePolicyMapper).findByPid("route_123");
//        verify(routePolicyMapper).updateById(mockPolicy);
//    }
//
//    @Test
//    void testDisable() {
//        mockPolicy.setStatus("enabled");
//
//        // 设置模拟行为
//        when(routePolicyMapper.findByPid("route_123")).thenReturn(mockPolicy);
//        when(routePolicyMapper.updateById(mockPolicy)).thenReturn(1);
//
//        // 执行测试
//        assertDoesNotThrow(() -> routePolicyService.disable("route_123"));
//
//        // 验证状态更新
//        assertEquals("disabled", mockPolicy.getStatus());
//
//        // 验证方法调用
//        verify(routePolicyMapper).findByPid("route_123");
//        verify(routePolicyMapper).updateById(mockPolicy);
//    }
//
//    @Test
//    void testFindByRouteType() {
//        List<RoutePolicy> policies = Arrays.asList(mockPolicy);
//
//        // 设置模拟行为
//        when(routePolicyMapper.findByRouteType("page")).thenReturn(policies);
//        when(routePolicyConverter.toDTO(mockPolicy)).thenReturn(mockPolicyDTO);
//
//        // 执行测试
//        List<RoutePolicyDTO> result = routePolicyService.findByRouteType("page");
//
//        // 验证结果
//        assertNotNull(result);
//        assertEquals(1, result.size());
//        assertEquals("page", result.get(0).getRouteType());
//
//        // 验证方法调用
//        verify(routePolicyMapper).findByRouteType("page");
//        verify(routePolicyConverter).toDTO(mockPolicy);
//    }
//
//    @Test
//    void testFindByStatus() {
//        List<RoutePolicy> policies = Arrays.asList(mockPolicy);
//
//        // 设置模拟行为
//        when(routePolicyMapper.findByStatus("enabled")).thenReturn(policies);
//        when(routePolicyConverter.toDTO(mockPolicy)).thenReturn(mockPolicyDTO);
//
//        // 执行测试
//        List<RoutePolicyDTO> result = routePolicyService.findByStatus("enabled");
//
//        // 验证结果
//        assertNotNull(result);
//        assertEquals(1, result.size());
//        assertEquals("enabled", result.get(0).getStatus());
//
//        // 验证方法调用
//        verify(routePolicyMapper).findByStatus("enabled");
//        verify(routePolicyConverter).toDTO(mockPolicy);
//    }
//
//    @Test
//    void testFindByPriority() {
//        List<RoutePolicy> policies = Arrays.asList(mockPolicy);
//
//        // 设置模拟行为
//        when(routePolicyMapper.findByPriority(100)).thenReturn(policies);
//        when(routePolicyConverter.toDTO(mockPolicy)).thenReturn(mockPolicyDTO);
//
//        // 执行测试
//        List<RoutePolicyDTO> result = routePolicyService.findByPriority(100);
//
//        // 验证结果
//        assertNotNull(result);
//        assertEquals(1, result.size());
//        assertEquals(100, result.get(0).getPriority());
//
//        // 验证方法调用
//        verify(routePolicyMapper).findByPriority(100);
//        verify(routePolicyConverter).toDTO(mockPolicy);
//    }
//
//    @Test
//    void testFindByPriorityRange() {
//        List<RoutePolicy> policies = Arrays.asList(mockPolicy);
//
//        // 设置模拟行为
//        when(routePolicyMapper.findByPriorityRange(50, 150)).thenReturn(policies);
//        when(routePolicyConverter.toDTO(mockPolicy)).thenReturn(mockPolicyDTO);
//
//        // 执行测试
//        List<RoutePolicyDTO> result = routePolicyService.findByPriorityRange(50, 150);
//
//        // 验证结果
//        assertNotNull(result);
//        assertEquals(1, result.size());
//        assertEquals(100, result.get(0).getPriority());
//
//        // 验证方法调用
//        verify(routePolicyMapper).findByPriorityRange(50, 150);
//        verify(routePolicyConverter).toDTO(mockPolicy);
//    }
//
//    @Test
//    void testUpdatePriority() {
//        // 设置模拟行为
//        when(routePolicyMapper.findByPid("route_123")).thenReturn(mockPolicy);
//        when(routePolicyMapper.updateById(mockPolicy)).thenReturn(1);
//
//        // 执行测试
//        assertDoesNotThrow(() -> routePolicyService.updatePriority("route_123", 200));
//
//        // 验证优先级更新
//        assertEquals(200, mockPolicy.getPriority());
//
//        // 验证方法调用
//        verify(routePolicyMapper).findByPid("route_123");
//        verify(routePolicyMapper).updateById(mockPolicy);
//    }
//
//    @Test
//    void testUpdateSortWeight() {
//        // 设置模拟行为
//        when(routePolicyMapper.findByPid("route_123")).thenReturn(mockPolicy);
//        when(routePolicyMapper.updateById(mockPolicy)).thenReturn(1);
//
//        // 执行测试
//        assertDoesNotThrow(() -> routePolicyService.updateSortWeight("route_123", 5));
//
//        // 验证排序权重更新
//        assertEquals(5, mockPolicy.getSortWeight());
//
//        // 验证方法调用
//        verify(routePolicyMapper).findByPid("route_123");
//        verify(routePolicyMapper).updateById(mockPolicy);
//    }
//
//    @Test
//    void testCopy() {
//        // 设置模拟行为
//        when(routePolicyMapper.findByPid("route_123")).thenReturn(mockPolicy);
//        when(routePolicyMapper.existsByName("测试路由策略_副本")).thenReturn(false);
//        when(routePolicyConverter.toEntity(any(RoutePolicyCreateRequest.class))).thenReturn(mockPolicy);
//        when(routePolicyMapper.insert(mockPolicy)).thenReturn(1);
//        when(routePolicyConverter.toDTO(mockPolicy)).thenReturn(mockPolicyDTO);
//
//        // 执行测试
//        RoutePolicyDTO result = routePolicyService.copy("route_123", "测试路由策略_副本");
//
//        // 验证结果
//        assertNotNull(result);
//
//        // 验证方法调用
//        verify(routePolicyMapper).findByPid("route_123");
//        verify(routePolicyMapper).existsByName("测试路由策略_副本");
//        verify(routePolicyConverter).toEntity(any(RoutePolicyCreateRequest.class));
//        verify(routePolicyMapper).insert(mockPolicy);
//        verify(routePolicyConverter).toDTO(mockPolicy);
//    }
//
//    @Test
//    void testTestRoute() {
//        Map<String, Object> testData = Map.of("path", "/test", "method", "get");
//
//        // 设置模拟行为
//        when(routePolicyMapper.findByPid("route_123")).thenReturn(mockPolicy);
//
//        // 执行测试
//        Map<String, Object> result = routePolicyService.testRoute("route_123", testData);
//
//        // 验证结果
//        assertNotNull(result);
//        assertTrue(result.containsKey("matched"));
//        assertTrue(result.containsKey("policy"));
//        assertTrue(result.containsKey("testData"));
//
//        // 验证方法调用
//        verify(routePolicyMapper).findByPid("route_123");
//    }
//
//    @Test
//    void testValidateConfig() {
//        Map<String, Object> config = Map.of("path", "/test", "method", "get");
//
//        // 执行测试
//        boolean result = routePolicyService.validateConfig(config);
//
//        // 验证结果
//        assertTrue(result);
//    }
//
//    @Test
//    void testValidateConfig_Invalid() {
//        Map<String, Object> config = Map.of(); // 空配置
//
//        // 执行测试
//        boolean result = routePolicyService.validateConfig(config);
//
//        // 验证结果
//        assertFalse(result);
//    }
//
//    @Test
//    void testCount() {
//        // 设置模拟行为
//        when(routePolicyMapper.countTotal(1L)).thenReturn(10L);
//
//        // 执行测试
//        long result = routePolicyService.count();
//
//        // 验证结果
//        assertEquals(10L, result);
//
//        // 验证方法调用
//        verify(routePolicyMapper).countTotal(1L);
//    }
//
//    @Test
//    void testCountByStatus() {
//        // 设置模拟行为
//        when(routePolicyMapper.countByStatus("enabled", 1L)).thenReturn(8L);
//
//        // 执行测试
//        long result = routePolicyService.countByStatus("enabled");
//
//        // 验证结果
//        assertEquals(8L, result);
//
//        // 验证方法调用
//        verify(routePolicyMapper).countByStatus("enabled", 1L);
//    }
//
//    @Test
//    void testCountByRouteType() {
//        // 设置模拟行为
//        when(routePolicyMapper.countByRouteType("page", 1L)).thenReturn(5L);
//
//        // 执行测试
//        long result = routePolicyService.countByRouteType("page");
//
//        // 验证结果
//        assertEquals(5L, result);
//
//        // 验证方法调用
//        verify(routePolicyMapper).countByRouteType("page", 1L);
//    }
//
//    @Test
//    void testExistsByName() {
//        // 设置模拟行为
//        when(routePolicyMapper.existsByName("测试路由策略")).thenReturn(true);
//
//        // 执行测试
//        boolean result = routePolicyService.existsByName("测试路由策略");
//
//        // 验证结果
//        assertTrue(result);
//
//        // 验证方法调用
//        verify(routePolicyMapper).existsByName("测试路由策略");
//    }
//
//    @Test
//    void testIsNameUnique() {
//        // 设置模拟行为
//        when(routePolicyMapper.existsByName("新路由策略")).thenReturn(false);
//
//        // 执行测试
//        boolean result = routePolicyService.isNameUnique("新路由策略");
//
//        // 验证结果
//        assertTrue(result);
//
//        // 验证方法调用
//        verify(routePolicyMapper).existsByName("新路由策略");
//    }
//
//    @Test
//    void testGetStatistics() {
//        // 设置模拟行为
//        when(routePolicyMapper.countTotal(1L)).thenReturn(10L);
//        when(routePolicyMapper.countByStatus("enabled", 1L)).thenReturn(8L);
//        when(routePolicyMapper.countByStatus("disabled", 1L)).thenReturn(2L);
//        when(routePolicyMapper.countByRouteType("page", 1L)).thenReturn(5L);
//        when(routePolicyMapper.countByRouteType("api", 1L)).thenReturn(3L);
//        when(routePolicyMapper.countByRouteType("component", 1L)).thenReturn(2L);
//
//        // 执行测试
//        Map<String, Object> result = routePolicyService.getStatistics();
//
//        // 验证结果
//        assertNotNull(result);
//        assertEquals(10L, result.get("totalCount"));
//        assertEquals(8L, result.get("enabledCount"));
//        assertEquals(2L, result.get("disabledCount"));
//
//        @SuppressWarnings("unchecked")
//        Map<String, Long> typeStats = (Map<String, Long>) result.get("typeStatistics");
//        assertEquals(5L, typeStats.get("page"));
//        assertEquals(3L, typeStats.get("api"));
//        assertEquals(2L, typeStats.get("component"));
//
//        // 验证方法调用
//        verify(routePolicyMapper).countTotal(1L);
//        verify(routePolicyMapper).countByStatus("enabled", 1L);
//        verify(routePolicyMapper).countByStatus("disabled", 1L);
//        verify(routePolicyMapper).countByRouteType("page", 1L);
//        verify(routePolicyMapper).countByRouteType("api", 1L);
//        verify(routePolicyMapper).countByRouteType("component", 1L);
//    }
//}