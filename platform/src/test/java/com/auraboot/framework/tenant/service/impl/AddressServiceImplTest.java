//package com.auraboot.framework.tenant.service.impl;
//
//import com.auraboot.framework.address.entity.Address;
//import com.auraboot.framework.application.tenant.TenantContext;
//import com.auraboot.framework.tenant.dao.mapper.AddressMapper;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.MockedStatic;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.util.Arrays;
//import java.util.Date;
//import java.util.List;
//
//import static org.junit.jupiter.api.Assertions.*;
//import static org.mockito.ArgumentMatchers.*;
//import static org.mockito.Mockito.*;
//
///**
// * 地址服务测试类
// */
//@ExtendWith(MockitoExtension.class)
//class AddressServiceImplTest {
//
//    @Mock
//    private AddressMapper addressMapper;
//
//    @InjectMocks
//    private AddressServiceImpl addressService;
//
//    private Address mockAddress;
//    private final Long TENANT_ID = 1L;
//    private final Long ADDRESS_ID = 1L;
//
//    @BeforeEach
//    void setUp() {
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
//    }
//
//    @Test
//    void testCreateAddress_Success() {
//        try (MockedStatic<TenantContext> mockedTenantContext = mockStatic(TenantContext.class)) {
//            mockedTenantContext.when(TenantContext::getCurrentTenantId).thenReturn(TENANT_ID);
//
//            when(addressMapper.insert(any(Address.class))).thenReturn(1);
//
//            Address result = addressService.createAddress(mockAddress);
//
//            assertNotNull(result);
//            assertEquals(TENANT_ID, result.getTenantId());
//            assertEquals("active", result.getStatus());
//            assertFalse(result.getDeletedFlag());
//            assertNotNull(result.getCreatedAt());
//            assertNotNull(result.getUpdatedAt());
//
//            verify(addressMapper).insert(any(Address.class));
//        }
//    }
//
//    @Test
//    void testUpdateAddress_Success() {
//        when(addressMapper.updateById(any(Address.class))).thenReturn(1);
//
//        Address result = addressService.updateAddress(mockAddress);
//
//        assertNotNull(result);
//        assertNotNull(result.getUpdatedAt());
//
//        verify(addressMapper).updateById(any(Address.class));
//    }
//
//    @Test
//    void testGetAddressById_Success() {
//        when(addressMapper.selectById(ADDRESS_ID)).thenReturn(mockAddress);
//
//        Address result = addressService.getAddressById(ADDRESS_ID);
//
//        assertNotNull(result);
//        assertEquals(ADDRESS_ID, result.getId());
//
//        verify(addressMapper).selectById(ADDRESS_ID);
//    }
//
//    @Test
//    void testGetAddressByPid_Success() {
//        Long pid = 100L;
//        when(addressMapper.selectOne(any())).thenReturn(mockAddress);
//
//        Address result = addressService.getAddressByPid(pid);
//
//        assertNotNull(result);
//        verify(addressMapper).selectOne(any());
//    }
//
//    @Test
//    void testGetAddressesByCity_Success() {
//        String city = "深圳市";
//        List<Address> mockAddresses = Arrays.asList(mockAddress);
//        when(addressMapper.selectList(any())).thenReturn(mockAddresses);
//
//        List<Address> result = addressService.getAddressesByCity(city);
//
//        assertNotNull(result);
//        assertEquals(1, result.size());
//        verify(addressMapper).selectList(any());
//    }
//
//    @Test
//    void testGetAddressesByProvince_Success() {
//        String province = "广东省";
//        List<Address> mockAddresses = Arrays.asList(mockAddress);
//        when(addressMapper.selectList(any())).thenReturn(mockAddresses);
//
//        List<Address> result = addressService.getAddressesByProvince(province);
//
//        assertNotNull(result);
//        assertEquals(1, result.size());
//        verify(addressMapper).selectList(any());
//    }
//
//    @Test
//    void testGetAddressesByDistrict_Success() {
//        String district = "南山区";
//        List<Address> mockAddresses = Arrays.asList(mockAddress);
//        when(addressMapper.selectList(any())).thenReturn(mockAddresses);
//
//        List<Address> result = addressService.getAddressesByDistrict(district);
//
//        assertNotNull(result);
//        assertEquals(1, result.size());
//        verify(addressMapper).selectList(any());
//    }
//
//    @Test
//    void testGetAddressesByStatus_Success() {
//        String status = "active";
//        List<Address> mockAddresses = Arrays.asList(mockAddress);
//        when(addressMapper.selectList(any())).thenReturn(mockAddresses);
//
//        List<Address> result = addressService.getAddressesByStatus(status);
//
//        assertNotNull(result);
//        assertEquals(1, result.size());
//        verify(addressMapper).selectList(any());
//    }
//
//    @Test
//    void testGetAddressesByPostalCode_Success() {
//        String postalCode = "518000";
//        List<Address> mockAddresses = Arrays.asList(mockAddress);
//        when(addressMapper.selectList(any())).thenReturn(mockAddresses);
//
//        List<Address> result = addressService.getAddressesByPostalCode(postalCode);
//
//        assertNotNull(result);
//        assertEquals(1, result.size());
//        verify(addressMapper).selectList(any());
//    }
//
//    @Test
//    void testGetDefaultAddress_Success() {
//        try (MockedStatic<TenantContext> mockedTenantContext = mockStatic(TenantContext.class)) {
//            mockedTenantContext.when(TenantContext::getCurrentTenantId).thenReturn(TENANT_ID);
//
//            mockAddress.setIsDefault(true);
//            when(addressMapper.selectOne(any())).thenReturn(mockAddress);
//
//            Address result = addressService.getDefaultAddress();
//
//            assertNotNull(result);
//            assertTrue(result.getIsDefault());
//            verify(addressMapper).selectOne(any());
//        }
//    }
//
//    @Test
//    void testSetDefaultAddress_Success() {
//        try (MockedStatic<TenantContext> mockedTenantContext = mockStatic(TenantContext.class)) {
//            mockedTenantContext.when(TenantContext::getCurrentTenantId).thenReturn(TENANT_ID);
//
//            when(addressMapper.selectById(ADDRESS_ID)).thenReturn(mockAddress);
//            when(addressMapper.update(any(), any())).thenReturn(1);
//            when(addressMapper.updateById(any(Address.class))).thenReturn(1);
//
//            boolean result = addressService.setDefaultAddress(ADDRESS_ID);
//
//            assertTrue(result);
//            verify(addressMapper).selectById(ADDRESS_ID);
//            verify(addressMapper).update(any(), any()); // 清除其他默认地址
//            verify(addressMapper).updateById(any(Address.class)); // 设置新的默认地址
//        }
//    }
//
//    @Test
//    void testSetDefaultAddress_AddressNotFound() {
//        when(addressMapper.selectById(ADDRESS_ID)).thenReturn(null);
//
//        boolean result = addressService.setDefaultAddress(ADDRESS_ID);
//
//        assertFalse(result);
//        verify(addressMapper).selectById(ADDRESS_ID);
//        verify(addressMapper, never()).update(any(), any());
//        verify(addressMapper, never()).updateById(any(Address.class));
//    }
//
//    @Test
//    void testSearchAddresses_Success() {
//        try (MockedStatic<TenantContext> mockedTenantContext = mockStatic(TenantContext.class)) {
//            mockedTenantContext.when(TenantContext::getCurrentTenantId).thenReturn(TENANT_ID);
//
//            String keyword = "深圳";
//            List<Address> mockAddresses = Arrays.asList(mockAddress);
//            when(addressMapper.selectList(any())).thenReturn(mockAddresses);
//
//            List<Address> result = addressService.searchAddresses(keyword);
//
//            assertNotNull(result);
//            assertEquals(1, result.size());
//            verify(addressMapper).selectList(any());
//        }
//    }
//
//    @Test
//    void testValidateAddress_ValidAddress() {
//        boolean result = addressService.validateAddress(mockAddress);
//
//        assertTrue(result);
//    }
//
//    @Test
//    void testValidateAddress_InvalidAddress_NullCountry() {
//        mockAddress.setCountry(null);
//
//        boolean result = addressService.validateAddress(mockAddress);
//
//        assertFalse(result);
//    }
//
//    @Test
//    void testValidateAddress_InvalidAddress_EmptyProvince() {
//        mockAddress.setProvince("");
//
//        boolean result = addressService.validateAddress(mockAddress);
//
//        assertFalse(result);
//    }
//
//    @Test
//    void testValidateAddress_InvalidAddress_BlankCity() {
//        mockAddress.setCity("   ");
//
//        boolean result = addressService.validateAddress(mockAddress);
//
//        assertFalse(result);
//    }
//
//    @Test
//    void testFormatAddressDisplay_Success() {
//        String result = addressService.formatAddressDisplay(mockAddress);
//
//        assertNotNull(result);
//        assertTrue(result.contains("中国"));
//        assertTrue(result.contains("广东省"));
//        assertTrue(result.contains("深圳市"));
//        assertTrue(result.contains("南山区"));
//        assertTrue(result.contains("科技园南区"));
//    }
//
//    @Test
//    void testFormatAddressDisplay_NullAddress() {
//        String result = addressService.formatAddressDisplay(null);
//
//        assertEquals("", result);
//    }
//
//    @Test
//    void testDeleteAddress_Success() {
//        when(addressMapper.selectById(ADDRESS_ID)).thenReturn(mockAddress);
//        when(addressMapper.updateById(any(Address.class))).thenReturn(1);
//
//        boolean result = addressService.deleteAddress(ADDRESS_ID);
//
//        assertTrue(result);
//        verify(addressMapper).selectById(ADDRESS_ID);
//        verify(addressMapper).updateById(any(Address.class));
//    }
//
//    @Test
//    void testDeleteAddress_AddressNotFound() {
//        when(addressMapper.selectById(ADDRESS_ID)).thenReturn(null);
//
//        boolean result = addressService.deleteAddress(ADDRESS_ID);
//
//        assertFalse(result);
//        verify(addressMapper).selectById(ADDRESS_ID);
//        verify(addressMapper, never()).updateById(any(Address.class));
//    }
//
//    @Test
//    void testBatchDeleteAddresses_Success() {
//        List<Long> addressIds = Arrays.asList(1L, 2L, 3L);
//        when(addressMapper.update(any(), any())).thenReturn(3);
//
//        boolean result = addressService.batchDeleteAddresses(addressIds);
//
//        assertTrue(result);
//        verify(addressMapper).update(any(), any());
//    }
//
//    @Test
//    void testBatchDeleteAddresses_EmptyList() {
//        List<Long> addressIds = Arrays.asList();
//
//        boolean result = addressService.batchDeleteAddresses(addressIds);
//
//        assertFalse(result);
//        verify(addressMapper, never()).update(any(), any());
//    }
//}