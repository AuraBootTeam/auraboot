package com.auraboot.framework.engagement.service.impl;

import com.auraboot.framework.engagement.dto.UserEngagementDTO;
import com.auraboot.framework.engagement.entity.UserEngagement;
import com.auraboot.framework.engagement.mapper.UserEngagementMapper;
import com.auraboot.framework.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserEngagementServiceImplTest {

    @Mock private UserEngagementMapper engagementMapper;
    @InjectMocks private UserEngagementServiceImpl service;

    private UserEngagement entity(Long id) {
        UserEngagement e = new UserEngagement();
        e.setId(id);
        e.setUserId(1L);
        e.setTenantId(2L);
        e.setTargetType("page");
        e.setTargetId("p1");
        e.setEngagementType("favorite");
        e.setSortOrder(0);
        return e;
    }

    @Test
    void list_appliesTargetTypeFilterAndMapsDto() {
        when(engagementMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(entity(10L)));
        List<UserEngagementDTO> result = service.list(1L, 2L, "favorite", "page");
        assertEquals(1, result.size());
        assertEquals("page", result.get(0).getTargetType());
    }

    @Test
    void list_blankTargetTypeStillWorks() {
        when(engagementMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        assertTrue(service.list(1L, 2L, "favorite", null).isEmpty());
    }

    @Test
    void upsert_createsNewWhenAbsent() {
        when(engagementMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        UserEngagementDTO dto = new UserEngagementDTO();
        dto.setTargetType("page");
        dto.setTargetId("p1");
        dto.setTargetLabel("Label");
        dto.setEngagementType("favorite");

        service.upsert(1L, 2L, dto);

        verify(engagementMapper).insert(argThat((UserEngagement e) -> e.getUserId() == 1L
                && e.getTenantId() == 2L
                && e.getSortOrder() == 0));
        verify(engagementMapper, never()).updateById(any(UserEngagement.class));
    }

    @Test
    void upsert_updatesExistingPreservesIdentity() {
        UserEngagement existing = entity(99L);
        when(engagementMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        UserEngagementDTO dto = new UserEngagementDTO();
        dto.setTargetType("page");
        dto.setTargetId("p1");
        dto.setTargetLabel("New Label");
        dto.setEngagementType("favorite");

        service.upsert(1L, 2L, dto);
        verify(engagementMapper).updateById(existing);
        verify(engagementMapper, never()).insert(any(UserEngagement.class));
        assertEquals("New Label", existing.getTargetLabel());
    }

    @Test
    void upsert_recentViewTriggersPruneWhenExceedingMax() {
        when(engagementMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        // 22 records: oldest 2 should be pruned (max 20)
        List<UserEngagement> all = new ArrayList<>();
        IntStream.range(0, 22).forEach(i -> all.add(entity((long) (1000 + i))));
        when(engagementMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(all);

        UserEngagementDTO dto = new UserEngagementDTO();
        dto.setTargetType("page");
        dto.setTargetId("p1");
        dto.setEngagementType("recent_view");

        service.upsert(1L, 2L, dto);
        verify(engagementMapper, times(2)).deleteById(anyLong());
    }

    @Test
    void upsert_recentViewBelowMaxNoDeletion() {
        when(engagementMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(engagementMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(entity(1L)));
        UserEngagementDTO dto = new UserEngagementDTO();
        dto.setEngagementType("recent_view");
        dto.setTargetType("page");
        dto.setTargetId("p1");
        service.upsert(1L, 2L, dto);
        verify(engagementMapper, never()).deleteById(anyLong());
    }

    @Test
    void upsert_usesDtoSortOrderWhenProvided() {
        when(engagementMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        UserEngagementDTO dto = new UserEngagementDTO();
        dto.setTargetType("t");
        dto.setTargetId("i");
        dto.setEngagementType("favorite");
        dto.setSortOrder(7);
        service.upsert(1L, 2L, dto);
        verify(engagementMapper).insert(argThat((UserEngagement e) -> e.getSortOrder() == 7));
    }

    @Test
    void delete_throwsWhenNotFound() {
        when(engagementMapper.selectById(5L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.delete(5L, 1L));
    }

    @Test
    void delete_throwsWhenNotOwned() {
        UserEngagement e = entity(5L);
        e.setUserId(99L);
        when(engagementMapper.selectById(5L)).thenReturn(e);
        assertThrows(BusinessException.class, () -> service.delete(5L, 1L));
    }

    @Test
    void delete_succeedsWhenOwned() {
        when(engagementMapper.selectById(5L)).thenReturn(entity(5L));
        service.delete(5L, 1L);
        verify(engagementMapper).deleteById(5L);
    }

    @Test
    void reorder_updatesEachOwnedRecordInOrder() {
        when(engagementMapper.selectById(10L)).thenReturn(entity(10L));
        when(engagementMapper.selectById(20L)).thenReturn(entity(20L));
        service.reorder(1L, List.of(10L, 20L));
        verify(engagementMapper, times(2)).updateById(any(UserEngagement.class));
    }

    @Test
    void reorder_skipsMissingOrNotOwned() {
        when(engagementMapper.selectById(10L)).thenReturn(null);
        UserEngagement other = entity(20L);
        other.setUserId(999L);
        when(engagementMapper.selectById(20L)).thenReturn(other);
        service.reorder(1L, List.of(10L, 20L));
        verify(engagementMapper, never()).updateById(any(UserEngagement.class));
    }
}
