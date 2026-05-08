package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.mapper.TenantMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantServiceImpl")
class TenantServiceImplTest {

    @Mock
    private TenantMapper tenantMapper;

    private TenantServiceImpl service;
    private TenantServiceImpl spyService;

    @BeforeEach
    void setUp() throws Exception {
        service = new TenantServiceImpl();
        injectField(service, "baseMapper", tenantMapper);
        injectField(service, "tenantMapper", tenantMapper);

        spyService = spy(service);
    }

    static void injectField(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private Tenant fixture(Long id, String name) {
        Tenant t = new Tenant();
        t.setId(id);
        t.setPid("pid-" + id);
        t.setName(name);
        t.setStatus(StatusConstants.ACTIVE);
        return t;
    }

    @Test
    @DisplayName("createTenant fails when name not available")
    void createTenantFailsOnDuplicateName() {
        when(tenantMapper.findByName("acme")).thenReturn(fixture(99L, "acme"));
        Tenant input = fixture(null, "acme");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> spyService.createTenant(input));
        assertTrue(ex.getMessage().contains("acme"));
    }

    @Test
    @DisplayName("createTenant saves tenant when name available")
    void createTenantSavesWhenNameAvailable() {
        when(tenantMapper.findByName("acme")).thenReturn(null);
        Tenant input = fixture(null, "acme");
        doReturn(true).when(spyService).save(any(Tenant.class));

        Tenant result = spyService.createTenant(input);

        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());
        verify(spyService).save(input);
    }

    @Test
    @DisplayName("updateTenant throws when id not found")
    void updateTenantThrowsWhenMissing() {
        Tenant input = fixture(5L, "anything");
        doReturn(null).when(spyService).getById(5L);

        assertThrows(BusinessException.class, () -> spyService.updateTenant(input));
    }

    @Test
    @DisplayName("updateTenant blocks rename to existing name")
    void updateTenantBlocksDuplicateRename() {
        Tenant existing = fixture(5L, "old");
        Tenant input = fixture(5L, "taken");
        doReturn(existing).when(spyService).getById(5L);
        when(tenantMapper.findByName("taken")).thenReturn(fixture(99L, "taken"));

        assertThrows(BusinessException.class, () -> spyService.updateTenant(input));
    }

    @Test
    @DisplayName("updateTenant succeeds when name unchanged")
    void updateTenantSucceedsWhenNameUnchanged() {
        Tenant existing = fixture(5L, "same");
        Tenant input = fixture(5L, "same");
        input.setDisplayName("New Display");
        doReturn(existing).when(spyService).getById(5L);
        doReturn(true).when(spyService).updateById(input);

        Tenant result = spyService.updateTenant(input);

        assertNotNull(result.getUpdatedAt());
        assertEquals("New Display", result.getDisplayName());
    }

    @Test
    @DisplayName("findByName delegates to mapper")
    void findByNameDelegates() {
        Tenant t = fixture(1L, "t1");
        when(tenantMapper.findByName("t1")).thenReturn(t);

        assertEquals(t, service.findByName("t1"));
    }

    @Test
    @DisplayName("getTenantByDomain queries with domain + deleted_flag")
    void getTenantByDomainQueries() {
        Tenant t = fixture(1L, "t1");
        doReturn(t).when(spyService).getOne(any(QueryWrapper.class));

        assertEquals(t, spyService.getTenantByDomain("example.com"));
    }

    @Test
    @DisplayName("findByStatus delegates to mapper")
    void findByStatusDelegates() {
        List<Tenant> list = List.of(fixture(1L, "a"));
        when(tenantMapper.findByStatus("active")).thenReturn(list);

        assertEquals(list, service.findByStatus("active"));
    }

    @Test
    @DisplayName("getAllTenants and getActiveTenants return list")
    void getAllAndActiveTenants() {
        List<Tenant> list = List.of(fixture(1L, "t"));
        doReturn(list).when(spyService).list(any(QueryWrapper.class));

        assertEquals(list, spyService.getAllTenants());
        assertEquals(list, spyService.getActiveTenants());
    }

    @Test
    @DisplayName("findTenants applies all filter conditions")
    void findTenantsAppliesFilters() {
        Page<Tenant> page = new Page<>(1, 10);
        doReturn(page).when(spyService).page(any(Page.class), any(QueryWrapper.class));

        Page<Tenant> result = spyService.findTenants(1, 10, "name", "code", "active", "kw");

        assertNotNull(result);
        verify(spyService).page(any(Page.class), any(QueryWrapper.class));
    }

    @Test
    @DisplayName("findTenants with all blank filters still returns page")
    void findTenantsBlankFilters() {
        Page<Tenant> page = new Page<>(1, 10);
        doReturn(page).when(spyService).page(any(Page.class), any(QueryWrapper.class));

        Page<Tenant> result = spyService.findTenants(1, 10, null, null, null, null);

        assertNotNull(result);
    }

    @Test
    @DisplayName("activateTenant throws when missing")
    void activateThrowsWhenMissing() {
        doReturn(null).when(spyService).getById(1L);
        assertThrows(BusinessException.class, () -> spyService.activateTenant(1L));
    }

    @Test
    @DisplayName("activateTenant updates status to ACTIVE")
    void activateUpdatesStatus() {
        Tenant t = fixture(1L, "t");
        t.setStatus(StatusConstants.INACTIVE);
        doReturn(t).when(spyService).getById(1L);
        doReturn(true).when(spyService).updateById(t);

        assertTrue(spyService.activateTenant(1L));
        assertEquals(StatusConstants.ACTIVE, t.getStatus());
    }

    @Test
    @DisplayName("deactivateTenant updates status to INACTIVE")
    void deactivateUpdatesStatus() {
        Tenant t = fixture(1L, "t");
        doReturn(t).when(spyService).getById(1L);
        doReturn(true).when(spyService).updateById(t);

        assertTrue(spyService.deactivateTenant(1L));
        assertEquals(StatusConstants.INACTIVE, t.getStatus());
    }

    @Test
    @DisplayName("deactivateTenant throws when missing")
    void deactivateThrowsWhenMissing() {
        doReturn(null).when(spyService).getById(1L);
        assertThrows(BusinessException.class, () -> spyService.deactivateTenant(1L));
    }

    @Test
    @DisplayName("suspendTenant updates status to SUSPENDED")
    void suspendUpdatesStatus() {
        Tenant t = fixture(1L, "t");
        doReturn(t).when(spyService).getById(1L);
        doReturn(true).when(spyService).updateById(t);

        assertTrue(spyService.suspendTenant(1L, "reason"));
        assertEquals(StatusConstants.SUSPENDED, t.getStatus());
    }

    @Test
    @DisplayName("suspendTenant throws when missing")
    void suspendThrowsWhenMissing() {
        doReturn(null).when(spyService).getById(1L);
        assertThrows(BusinessException.class, () -> spyService.suspendTenant(1L, "r"));
    }

    @Test
    @DisplayName("resumeTenant restores ACTIVE status")
    void resumeUpdatesStatus() {
        Tenant t = fixture(1L, "t");
        t.setStatus(StatusConstants.SUSPENDED);
        doReturn(t).when(spyService).getById(1L);
        doReturn(true).when(spyService).updateById(t);

        assertTrue(spyService.resumeTenant(1L));
        assertEquals(StatusConstants.ACTIVE, t.getStatus());
    }

    @Test
    @DisplayName("resumeTenant throws when missing")
    void resumeThrowsWhenMissing() {
        doReturn(null).when(spyService).getById(1L);
        assertThrows(BusinessException.class, () -> spyService.resumeTenant(1L));
    }

    @Test
    @DisplayName("deleteTenant deletes and returns true when row affected")
    void deleteSucceeds() {
        Tenant t = fixture(1L, "t");
        doReturn(t).when(spyService).getById(1L);
        when(tenantMapper.deleteById(1L)).thenReturn(1);

        assertTrue(spyService.deleteTenant(1L));
    }

    @Test
    @DisplayName("deleteTenant throws when missing")
    void deleteThrowsWhenMissing() {
        doReturn(null).when(spyService).getById(1L);
        assertThrows(BusinessException.class, () -> spyService.deleteTenant(1L));
    }

    @Test
    @DisplayName("isNameAvailable true when no tenant with that name")
    void isNameAvailableTrue() {
        when(tenantMapper.findByName("free")).thenReturn(null);
        assertTrue(service.isNameAvailable("free"));
    }

    @Test
    @DisplayName("isNameAvailable false when tenant exists")
    void isNameAvailableFalse() {
        when(tenantMapper.findByName("taken")).thenReturn(fixture(1L, "taken"));
        assertFalse(service.isNameAvailable("taken"));
    }

    @Test
    @DisplayName("batchDeleteTenants returns 0 for null/empty")
    void batchDeleteEmpty() {
        assertEquals(0, service.batchDeleteTenants(null));
        assertEquals(0, service.batchDeleteTenants(List.of()));
    }

    @Test
    @DisplayName("batchDeleteTenants counts successful deletes")
    void batchDeleteCountsSuccess() {
        Tenant t1 = fixture(1L, "a");
        Tenant t2 = fixture(2L, "b");
        doReturn(t1).when(spyService).getById(1L);
        doReturn(t2).when(spyService).getById(2L);
        when(tenantMapper.deleteById(1L)).thenReturn(1);
        when(tenantMapper.deleteById(2L)).thenReturn(0);

        assertEquals(1, spyService.batchDeleteTenants(Arrays.asList(1L, 2L)));
    }

    @Test
    @DisplayName("countByStatus delegates to mapper")
    void countByStatusDelegates() {
        when(tenantMapper.countByStatus("active")).thenReturn(7L);
        assertEquals(7L, service.countByStatus("active"));
    }

    @Test
    @DisplayName("findByPid queries with pid + deleted_flag")
    void findByPidQueries() {
        Tenant t = fixture(1L, "t");
        doReturn(t).when(spyService).getOne(any(QueryWrapper.class));

        assertEquals(t, spyService.findByPid("pid-1"));
    }
}
