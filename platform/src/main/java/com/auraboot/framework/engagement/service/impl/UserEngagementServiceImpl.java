package com.auraboot.framework.engagement.service.impl;

import com.auraboot.framework.engagement.dto.UserEngagementDTO;
import com.auraboot.framework.engagement.entity.UserEngagement;
import com.auraboot.framework.engagement.mapper.UserEngagementMapper;
import com.auraboot.framework.engagement.service.UserEngagementService;
import com.auraboot.framework.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserEngagementServiceImpl implements UserEngagementService {

    /** Maximum recent_view records kept per user per tenant. */
    private static final int RECENT_VIEW_MAX = 20;

    /** Engagement type constant for recently-viewed items. */
    private static final String TYPE_RECENT_VIEW = "recent_view";

    private final UserEngagementMapper engagementMapper;

    @Override
    public List<UserEngagementDTO> list(Long userId, Long tenantId, String engagementType, String targetType) {
        LambdaQueryWrapper<UserEngagement> wrapper = new LambdaQueryWrapper<UserEngagement>()
                .eq(UserEngagement::getUserId, userId)
                .eq(UserEngagement::getTenantId, tenantId)
                .eq(UserEngagement::getEngagementType, engagementType)
                .eq(StringUtils.hasText(targetType), UserEngagement::getTargetType, targetType)
                .orderByAsc(UserEngagement::getSortOrder)
                .orderByDesc(UserEngagement::getUpdatedAt);

        return engagementMapper.selectList(wrapper)
                .stream()
                .map(this::toDTO)
                .toList();
    }

    @Override
    @Transactional
    public UserEngagementDTO upsert(Long userId, Long tenantId, UserEngagementDTO dto) {
        // Look up by composite unique key
        UserEngagement existing = engagementMapper.selectOne(
                new LambdaQueryWrapper<UserEngagement>()
                        .eq(UserEngagement::getUserId, userId)
                        .eq(UserEngagement::getTenantId, tenantId)
                        .eq(UserEngagement::getTargetType, dto.getTargetType())
                        .eq(UserEngagement::getTargetId, dto.getTargetId())
                        .eq(UserEngagement::getEngagementType, dto.getEngagementType())
        );

        UserEngagement entity;
        if (existing != null) {
            // Update mutable fields
            existing.setTargetLabel(dto.getTargetLabel());
            existing.setTargetContext(dto.getTargetContext());
            existing.setUpdatedAt(OffsetDateTime.now());
            engagementMapper.updateById(existing);
            entity = existing;
        } else {
            entity = new UserEngagement();
            entity.setUserId(userId);
            entity.setTenantId(tenantId);
            entity.setTargetType(dto.getTargetType());
            entity.setTargetId(dto.getTargetId());
            entity.setTargetLabel(dto.getTargetLabel());
            entity.setTargetContext(dto.getTargetContext());
            entity.setEngagementType(dto.getEngagementType());
            entity.setSortOrder(dto.getSortOrder() != null ? dto.getSortOrder() : 0);
            // createdAt / updatedAt filled by MetaObjectHandler via @TableField(fill)
            engagementMapper.insert(entity);
        }

        // Prune oldest recent_view records if over the limit
        if (TYPE_RECENT_VIEW.equals(dto.getEngagementType())) {
            pruneRecentViews(userId, tenantId);
        }

        return toDTO(entity);
    }

    @Override
    @Transactional
    public void delete(Long id, Long userId) {
        UserEngagement entity = engagementMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("Engagement record not found: " + id);
        }
        if (!entity.getUserId().equals(userId)) {
            throw new BusinessException("Access denied: engagement record does not belong to current user");
        }
        engagementMapper.deleteById(id);
    }

    @Override
    @Transactional
    public void reorder(Long userId, List<Long> orderedIds) {
        for (int i = 0; i < orderedIds.size(); i++) {
            Long recordId = orderedIds.get(i);
            UserEngagement entity = engagementMapper.selectById(recordId);
            if (entity == null || !entity.getUserId().equals(userId)) {
                log.warn("Skipping reorder for id={}: not found or not owned by userId={}", recordId, userId);
                continue;
            }
            entity.setSortOrder(i);
            entity.setUpdatedAt(OffsetDateTime.now());
            engagementMapper.updateById(entity);
        }
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    /**
     * Prune the oldest recent_view records beyond the RECENT_VIEW_MAX cap.
     * Selects all records ordered by createdAt ASC and deletes everything past the limit.
     */
    private void pruneRecentViews(Long userId, Long tenantId) {
        List<UserEngagement> all = engagementMapper.selectList(
                new LambdaQueryWrapper<UserEngagement>()
                        .eq(UserEngagement::getUserId, userId)
                        .eq(UserEngagement::getTenantId, tenantId)
                        .eq(UserEngagement::getEngagementType, TYPE_RECENT_VIEW)
                        .orderByAsc(UserEngagement::getCreatedAt)
        );

        if (all.size() > RECENT_VIEW_MAX) {
            List<UserEngagement> toDelete = all.subList(0, all.size() - RECENT_VIEW_MAX);
            for (UserEngagement old : toDelete) {
                engagementMapper.deleteById(old.getId());
            }
            log.debug("Pruned {} oldest recent_view records for userId={} tenantId={}",
                    toDelete.size(), userId, tenantId);
        }
    }

    /**
     * Map entity → DTO.
     */
    private UserEngagementDTO toDTO(UserEngagement entity) {
        UserEngagementDTO dto = new UserEngagementDTO();
        dto.setId(entity.getId());
        dto.setTargetType(entity.getTargetType());
        dto.setTargetId(entity.getTargetId());
        dto.setTargetLabel(entity.getTargetLabel());
        dto.setEngagementType(entity.getEngagementType());
        dto.setSortOrder(entity.getSortOrder());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setTargetContext(entity.getTargetContext());

        return dto;
    }
}
