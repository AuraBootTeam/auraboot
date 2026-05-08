package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantMemberServiceImpl")
class TenantMemberServiceImplTest {

    @Mock
    private TenantMemberMapper tenantMemberMapper;
    @Mock
    private SystemModeService systemModeService;

    private TenantMemberServiceImpl service;
    private TenantMemberServiceImpl spyService;

    @BeforeEach
    void setUp() throws Exception {
        service = new TenantMemberServiceImpl();
        TenantServiceImplTest.injectField(service, "baseMapper", tenantMemberMapper);
        TenantServiceImplTest.injectField(service, "tenantMemberMapper", tenantMemberMapper);
        TenantServiceImplTest.injectField(service, "objectMapper", new ObjectMapper());
        TenantServiceImplTest.injectField(service, "systemModeService", systemModeService);
        spyService = spy(service);
    }

    private TenantMember member(Long id, Long tenantId, Long userId, String status) {
        TenantMember m = new TenantMember();
        m.setId(id);
        m.setPid("pid-" + id);
        m.setTenantId(tenantId);
        m.setUserId(userId);
        m.setStatus(status);
        return m;
    }

    @Test
    @DisplayName("findByPid uses lambda query")
    void findByPid() {
        TenantMember m = member(1L, 10L, 7L, StatusConstants.ACTIVE);
        doReturn(m).when(spyService).getOne(any(QueryWrapper.class));

        assertEquals(m, spyService.findByPid("pid-1"));
    }

    @Test
    @DisplayName("findByTenantIdAndUserId delegates to mapper")
    void findByTenantIdAndUserId() {
        TenantMember m = member(1L, 10L, 7L, StatusConstants.ACTIVE);
        when(tenantMemberMapper.findByTenantIdAndUserId(10L, 7L)).thenReturn(m);

        assertEquals(m, service.findByTenantIdAndUserId(10L, 7L));
    }

    @Test
    @DisplayName("addMember throws when already member")
    void addMemberDuplicate() {
        when(tenantMemberMapper.findByTenantIdAndUserId(10L, 7L))
                .thenReturn(member(1L, 10L, 7L, StatusConstants.ACTIVE));

        assertThrows(BusinessException.class,
                () -> spyService.addMember(7L, 10L, StatusConstants.ACTIVE));
    }

    @Test
    @DisplayName("addMember saves new member with audit fields")
    void addMemberSaves() {
        when(tenantMemberMapper.findByTenantIdAndUserId(10L, 7L)).thenReturn(null);
        doReturn(true).when(spyService).save(any(TenantMember.class));

        TenantMember added = spyService.addMember(7L, 10L, StatusConstants.PENDING);

        assertEquals(7L, added.getUserId());
        assertEquals(10L, added.getTenantId());
        assertEquals(StatusConstants.PENDING, added.getStatus());
        assertNotNull(added.getPid());
        assertNotNull(added.getJoinDate());
    }

    @Test
    @DisplayName("updateMember throws when missing")
    void updateMemberMissing() {
        TenantMember m = member(1L, 10L, 7L, StatusConstants.ACTIVE);
        doReturn(null).when(spyService).getById(1L);

        assertThrows(BusinessException.class, () -> spyService.updateMember(m));
    }

    @Test
    @DisplayName("updateMember normalizes teamIds from settings JSON")
    void updateMemberNormalizesTeamIds() {
        TenantMember existing = member(1L, 10L, 7L, StatusConstants.ACTIVE);
        TenantMember m = member(1L, 10L, 7L, StatusConstants.ACTIVE);
        m.setSettings("{\"teamIds\":[\"T1\",\"T2\"]}");
        doReturn(existing).when(spyService).getById(1L);
        doReturn(true).when(spyService).updateById(m);

        TenantMember updated = spyService.updateMember(m);

        assertNotNull(updated.getSettings());
        assertTrue(updated.getSettings().toLowerCase().contains("t1"));
        assertTrue(updated.getSettings().toLowerCase().contains("t2"));
    }

    @Test
    @DisplayName("updateMember rejects invalid settings JSON")
    void updateMemberInvalidJson() {
        TenantMember existing = member(1L, 10L, 7L, StatusConstants.ACTIVE);
        TenantMember m = member(1L, 10L, 7L, StatusConstants.ACTIVE);
        m.setSettings("{not-json");
        doReturn(existing).when(spyService).getById(1L);

        assertThrows(BusinessException.class, () -> spyService.updateMember(m));
    }

    @Test
    @DisplayName("findByTenantId delegates to mapper")
    void findByTenantId() {
        List<TenantMember> list = List.of(member(1L, 10L, 7L, StatusConstants.ACTIVE));
        when(tenantMemberMapper.findByTenantId(10L)).thenReturn(list);

        assertEquals(list, service.findByTenantId(10L));
    }

    @Test
    @DisplayName("findMembers paginates with status filter")
    void findMembersWithStatus() {
        Page<TenantMember> page = new Page<>(1, 10);
        doReturn(page).when(spyService).page(any(Page.class), any(QueryWrapper.class));

        Page<TenantMember> result = spyService.findMembers(1, 10, 10L, "kw", "human", "active");
        assertNotNull(result);
    }

    @Test
    @DisplayName("findMembers paginates without status filter")
    void findMembersNoStatus() {
        Page<TenantMember> page = new Page<>(1, 10);
        doReturn(page).when(spyService).page(any(Page.class), any(QueryWrapper.class));

        assertNotNull(spyService.findMembers(1, 10, 10L, null, null, null));
    }

    @Test
    @DisplayName("activateMember throws when missing")
    void activateMissing() {
        doReturn(null).when(spyService).getById(1L);
        assertThrows(BusinessException.class, () -> spyService.activateMember(1L));
    }

    @Test
    @DisplayName("activateMember sets ACTIVE status")
    void activateOk() {
        TenantMember m = member(1L, 10L, 7L, StatusConstants.PENDING);
        doReturn(m).when(spyService).getById(1L);
        doReturn(true).when(spyService).updateById(m);

        assertTrue(spyService.activateMember(1L));
        assertEquals(StatusConstants.ACTIVE, m.getStatus());
    }

    @Test
    @DisplayName("deactivateMember sets INACTIVE + leaveDate")
    void deactivateOk() {
        TenantMember m = member(1L, 10L, 7L, StatusConstants.ACTIVE);
        doReturn(m).when(spyService).getById(1L);
        doReturn(true).when(spyService).updateById(m);

        assertTrue(spyService.deactivateMember(1L));
        assertEquals(StatusConstants.INACTIVE, m.getStatus());
        assertNotNull(m.getLeaveDate());
    }

    @Test
    @DisplayName("deactivateMember throws when missing")
    void deactivateMissing() {
        doReturn(null).when(spyService).getById(1L);
        assertThrows(BusinessException.class, () -> spyService.deactivateMember(1L));
    }

    @Test
    @DisplayName("suspendMember sets SUSPENDED status")
    void suspendOk() {
        TenantMember m = member(1L, 10L, 7L, StatusConstants.ACTIVE);
        doReturn(m).when(spyService).getById(1L);
        doReturn(true).when(spyService).updateById(m);

        assertTrue(spyService.suspendMember(1L, "violation"));
        assertEquals(StatusConstants.SUSPENDED, m.getStatus());
    }

    @Test
    @DisplayName("suspendMember throws when missing")
    void suspendMissing() {
        doReturn(null).when(spyService).getById(1L);
        assertThrows(BusinessException.class, () -> spyService.suspendMember(1L, "r"));
    }

    @Test
    @DisplayName("removeMember sets leave date and removes")
    void removeMemberOk() {
        TenantMember m = member(1L, 10L, 7L, StatusConstants.ACTIVE);
        doReturn(m).when(spyService).getById(1L);
        doReturn(true).when(spyService).updateById(m);
        doReturn(true).when(spyService).removeById(1L);

        assertTrue(spyService.removeMember(1L));
        assertNotNull(m.getLeaveDate());
    }

    @Test
    @DisplayName("removeMember throws when missing")
    void removeMemberMissing() {
        doReturn(null).when(spyService).getById(1L);
        assertThrows(BusinessException.class, () -> spyService.removeMember(1L));
    }

    @Test
    @DisplayName("findLeavingMembers queries by date range")
    void findLeavingMembers() {
        List<TenantMember> list = List.of(member(1L, 10L, 7L, StatusConstants.ACTIVE));
        doReturn(list).when(spyService).list(any(QueryWrapper.class));

        assertEquals(list, spyService.findLeavingMembers(7));
    }

    @Test
    @DisplayName("getTenantIdsByUserId delegates to mapper")
    void getTenantIdsByUserId() {
        List<Long> ids = Arrays.asList(1L, 2L);
        when(tenantMemberMapper.getTenantIdsByUserId(7L)).thenReturn(ids);

        assertEquals(ids, service.getTenantIdsByUserId(7L));
    }

    @Test
    @DisplayName("getTenantIdByUserId returns null when no tenants")
    void getTenantIdByUserIdEmpty() {
        when(tenantMemberMapper.getTenantIdsByUserId(7L)).thenReturn(List.of());
        assertNull(service.getTenantIdByUserId(7L));
    }

    @Test
    @DisplayName("getTenantIdByUserId returns single id")
    void getTenantIdByUserIdSingle() {
        when(tenantMemberMapper.getTenantIdsByUserId(7L)).thenReturn(List.of(99L));
        assertEquals(99L, service.getTenantIdByUserId(7L));
    }

    @Test
    @DisplayName("getTenantIdByUserId SINGLE mode auto-selects default")
    void getTenantIdSingleModeDefault() {
        when(tenantMemberMapper.getTenantIdsByUserId(7L)).thenReturn(Arrays.asList(99L, 100L));
        when(systemModeService.isSingleTenant()).thenReturn(true);
        when(systemModeService.getDefaultTenantId()).thenReturn(100L);

        assertEquals(100L, service.getTenantIdByUserId(7L));
    }

    @Test
    @DisplayName("getTenantIdByUserId SINGLE mode falls back to non-System tenant")
    void getTenantIdSingleModeFallback() {
        when(tenantMemberMapper.getTenantIdsByUserId(7L)).thenReturn(Arrays.asList(99L, 100L));
        when(systemModeService.isSingleTenant()).thenReturn(true);
        when(systemModeService.getDefaultTenantId()).thenReturn(null);
        when(tenantMemberMapper.getTenantNameById(99L)).thenReturn("System");
        when(tenantMemberMapper.getTenantNameById(100L)).thenReturn("Acme");

        assertEquals(100L, service.getTenantIdByUserId(7L));
    }

    @Test
    @DisplayName("getTenantIdByUserId MULTI mode returns null when multiple tenants")
    void getTenantIdMultiMode() {
        when(tenantMemberMapper.getTenantIdsByUserId(7L)).thenReturn(Arrays.asList(99L, 100L));
        when(systemModeService.isSingleTenant()).thenReturn(false);

        assertNull(service.getTenantIdByUserId(7L));
    }

    @Test
    @DisplayName("getTenantNameById returns null for null id")
    void getTenantNameNull() {
        assertNull(service.getTenantNameById(null));
    }

    @Test
    @DisplayName("getTenantNameById delegates to mapper")
    void getTenantNameDelegates() {
        when(tenantMemberMapper.getTenantNameById(10L)).thenReturn("Acme");
        assertEquals("Acme", service.getTenantNameById(10L));
    }

    @Test
    @DisplayName("countByTenantId returns 0 for null")
    void countByTenantIdNull() {
        assertEquals(0L, service.countByTenantId(null));
    }

    @Test
    @DisplayName("countByTenantId delegates to mapper")
    void countByTenantId() {
        when(tenantMemberMapper.countByTenantIdAndStatus(10L, "active")).thenReturn(5L);
        assertEquals(5L, service.countByTenantId(10L));
    }
}
