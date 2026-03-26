//package com.auraboot.framework.tenant.service.impl;
//
//import com.auraboot.framework.address.entity.Address;
//import com.auraboot.framework.application.tenant.TenantContext;
//import com.auraboot.framework.tenant.dao.entity.Store;
//import com.auraboot.framework.store.dto.StoreRequest;
//import com.auraboot.framework.store.dto.StoreResponse;
//import com.auraboot.framework.store.dto.StoreQueryRequest;
//import com.auraboot.framework.address.service.AddressService;
//import com.auraboot.framework.store.service.impl.StoreService;
//import com.auraboot.framework.meta.dto.PaginationResult;
//import com.auraboot.framework.user.dao.entity.User;
//import com.auraboot.framework.user.service.UserService;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.MockedStatic;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.util.Date;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
///**
// * 门店应用服务测试类
// */
//@ExtendWith(MockitoExtension.class)
//class StoreApplicationServiceImplTest {
//
//    @Mock
//    private StoreService storeService;
//
//    @Mock
//    private AddressService addressService;
//
//    @Mock
//    private UserService userService;
//
//    @InjectMocks
//    private StoreApplicationServiceImpl storeApplicationService;
//
//    private User mockUser;
//    private Store mockStore;
//    private Address mockAddress;
//    private StoreRequest storeRequest;
//    private final Long TENANT_ID = 1L;
//    private final Long USER_ID = 1L;
//    private final Long STORE_ID = 1L;
//    private final Long ADDRESS_ID = 1L;
//
//    @BeforeEach
//    void setUp() {
//        // 初始化测试数据
//        mockUser = new User();
//        mockUser.setId(USER_ID);
//        mockUser.setUsername("testuser");
//
//        mockAddress = new Address();
//        mockAddress.setId(ADDRESS_ID);
//        mockAddress.setTenantId(TENANT_ID);
//        mockAddress.setCountry("中国");
//        mockAddress.setProvince("广东省");
//        mockAddress.setCity("深圳市");
//        mockAddress.setDistrict("南山区");
//        mockAddress.setStreet("科技园南区");
//        mockAddress.setPostalCode("518000");
//        mockAddress.setStatus("active");
//        mockAddress.setIsDefault(false);
//        mockAddress.setDeletedFlag(false);
//        mockAddress.setCreatedAt(new Date());
//        mockAddress.setUpdatedAt(new Date());
//
//        mockStore = new Store();
//        mockStore.setId(STORE_ID);
//        mockStore.setTenantId(TENANT_ID);
//        mockStore.setAddressId(ADDRESS_ID);
//        mockStore.setName("测试门店");
//        mockStore.setCode("test001");
//        mockStore.setType("flagship");
//        mockStore.setStatus("active");
//        mockStore.setOpenDate(new Date());
//        mockStore.setExtension("{\"contactPhone\":\"13800138000\"}");
//        mockStore.setCreatedAt(new Date());
//        mockStore.setUpdatedAt(new Date());
//
//        storeRequest = new StoreRequest();
//        storeRequest.setName("测试门店");
//        storeRequest.setCode("test001");
//        storeRequest.setType("flagship");
//        storeRequest.setStatus("active");
//        storeRequest.setProvinceName("广东省");
//        storeRequest.setCityName("深圳市");
//        storeRequest.setDistrictName("南山区");
//        storeRequest.setDetailAddress("科技园南区");
//        storeRequest.setPostalCode("518000");
//        storeRequest.setContactPhone("13800138000");
//        storeRequest.setDescription("测试门店描述");
//    }
//
//    @Test
//    void testCreateStore_Success() {
//        // 模拟TenantContext
//        try (MockedStatic<TenantContext> mockedTenantContext = mockStatic(TenantContext.class)) {
//            mockedTenantContext.when(TenantContext::getCurrentTenantId).thenReturn(TENANT_ID);
//
//            // 模拟依赖服务
//            when(userService.findByUserId(USER_ID)).thenReturn(mockUser);
//            when(storeApplicationService.isStoreCodeAvailable(storeRequest.getCode(), TENANT_ID, null)).thenReturn(true);
//            when(addressService.createAddress(any(Address.class))).thenReturn(mockAddress);
//            when(storeService.save(any(Store.class))).thenReturn(true);
//
//            // 执行测试
//            StoreResponse result = storeApplicationService.createStore(storeRequest, USER_ID);
//
//            // 验证结果
//            assertNotNull(result);
//            assertEquals(mockStore.getName(), result.getName());
//            assertEquals(mockStore.getCode(), result.getCode());
//            assertEquals(mockStore.getType(), result.getType());
//            assertEquals(mockStore.getStatus(), result.getStatus());
//
//            // 验证地址信息
//            assertNotNull(result.getAddress());
//            assertEquals(mockAddress.getProvince(), result.getAddress().getProvince());
//            assertEquals(mockAddress.getCity(), result.getAddress().getCity());
//            assertEquals(mockAddress.getDistrict(), result.getAddress().getDistrict());
//
//            // 验证方法调用
//            verify(userService).findByUserId(USER_ID);
//            verify(addressService).createAddress(any(Address.class));
//            verify(storeService).save(any(Store.class));
//        }
//    }
//
//    @Test
//    void testCreateStore_UserNotFound() {
//        try (MockedStatic<TenantContext> mockedTenantContext = mockStatic(TenantContext.class)) {
//            mockedTenantContext.when(TenantContext::getCurrentTenantId).thenReturn(TENANT_ID);
//
//            when(userService.findByUserId(USER_ID)).thenReturn(null);
//
//            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
//                storeApplicationService.createStore(storeRequest, USER_ID);
//            });
//
//            assertEquals("用户不存在", exception.getMessage());
//        }
//    }
//
//    @Test
//    void testCreateStore_DuplicateStoreCode() {
//        try (MockedStatic<TenantContext> mockedTenantContext = mockStatic(TenantContext.class)) {
//            mockedTenantContext.when(TenantContext::getCurrentTenantId).thenReturn(TENANT_ID);
//
//            when(userService.findByUserId(USER_ID)).thenReturn(mockUser);
//            when(storeApplicationService.isStoreCodeAvailable(storeRequest.getCode(), TENANT_ID, null)).thenReturn(false);
//
//            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
//                storeApplicationService.createStore(storeRequest, USER_ID);
//            });
//
//            assertEquals("门店编码已存在", exception.getMessage());
//        }
//    }
//
//    @Test
//    void testUpdateStore_Success() {
//        try (MockedStatic<TenantContext> mockedTenantContext = mockStatic(TenantContext.class)) {
//            mockedTenantContext.when(TenantContext::getCurrentTenantId).thenReturn(TENANT_ID);
//
//            when(userService.findByUserId(USER_ID)).thenReturn(mockUser);
//            when(storeService.getById(STORE_ID)).thenReturn(mockStore);
//            when(storeApplicationService.isStoreCodeAvailable(storeRequest.getCode(), TENANT_ID, STORE_ID)).thenReturn(true);
//            when(addressService.getAddressById(ADDRESS_ID)).thenReturn(mockAddress);
//            when(addressService.updateAddress(any(Address.class))).thenReturn(mockAddress);
//            when(storeService.updateById(any(Store.class))).thenReturn(true);
//
//            StoreResponse result = storeApplicationService.updateStore(STORE_ID, storeRequest, USER_ID);
//
//            assertNotNull(result);
//            verify(storeService).updateById(any(Store.class));
//            verify(addressService).updateAddress(any(Address.class));
//        }
//    }
//
//    @Test
//    void testGetStoreById_Success() {
//        try (MockedStatic<TenantContext> mockedTenantContext = mockStatic(TenantContext.class)) {
//            mockedTenantContext.when(TenantContext::getCurrentTenantId).thenReturn(TENANT_ID);
//
//            when(storeService.getById(STORE_ID)).thenReturn(mockStore);
//            when(addressService.getAddressById(ADDRESS_ID)).thenReturn(mockAddress);
//
//            StoreResponse result = storeApplicationService.getStoreById(STORE_ID, USER_ID);
//
//            assertNotNull(result);
//            assertEquals(mockStore.getId(), result.getId());
//            assertEquals(mockStore.getName(), result.getName());
//            assertNotNull(result.getAddress());
//        }
//    }
//
//    @Test
//    void testGetStoreById_StoreNotFound() {
//        try (MockedStatic<TenantContext> mockedTenantContext = mockStatic(TenantContext.class)) {
//            mockedTenantContext.when(TenantContext::getCurrentTenantId).thenReturn(TENANT_ID);
//
//            when(storeService.getById(STORE_ID)).thenReturn(null);
//
//            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
//                storeApplicationService.getStoreById(STORE_ID, USER_ID);
//            });
//
//            assertEquals("门店不存在", exception.getMessage());
//        }
//    }
//
//    @Test
//    void testSearchStores_Success() {
//        try (MockedStatic<TenantContext> mockedTenantContext = mockStatic(TenantContext.class)) {
//            mockedTenantContext.when(TenantContext::getCurrentTenantId).thenReturn(TENANT_ID);
//
//            StoreQueryRequest queryRequest = new StoreQueryRequest();
//            queryRequest.setKeyword("测试");
//            queryRequest.setPageNum(1);
//            queryRequest.setPageSize(10);
//
//            when(userService.findByUserId(USER_ID)).thenReturn(mockUser);
//
//            // 这里需要根据实际的searchStores实现来模拟返回值
//            // 由于方法较复杂，这里只做基本验证
//            assertDoesNotThrow(() -> {
//                storeApplicationService.searchStores(queryRequest, USER_ID);
//            });
//        }
//    }
//
//    @Test
//    void testIsStoreCodeAvailable_Available() {
//        when(storeService.count(any())).thenReturn(0L);
//
//        boolean result = storeApplicationService.isStoreCodeAvailable("test001", TENANT_ID, null);
//
//        assertTrue(result);
//    }
//
//    @Test
//    void testIsStoreCodeAvailable_NotAvailable() {
//        when(storeService.count(any())).thenReturn(1L);
//
//        boolean result = storeApplicationService.isStoreCodeAvailable("test001", TENANT_ID, null);
//
//        assertFalse(result);
//    }
//}