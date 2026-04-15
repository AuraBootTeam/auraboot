package com.auraboot.framework.workbench.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.framework.workbench.dto.AnnouncementDTO;
import com.auraboot.framework.workbench.dto.AnnouncementRequest;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.AnnouncementMapper;
import com.auraboot.framework.workbench.service.AnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Implementation of AnnouncementService.
 *
 * @since 6.5.0
 */
@Service
@RequiredArgsConstructor
public class AnnouncementServiceImpl implements AnnouncementService {

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_DRAFT = "draft";
    private static final String PRIORITY_NORMAL = "normal";

    private final AnnouncementMapper announcementMapper;
    private final UserService userService;

    @Override
    public List<AnnouncementDTO> listActive(int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Announcement> announcements = announcementMapper.findByStatus(tenantId, STATUS_ACTIVE, limit);
        return announcements.stream().map(this::toDTO).toList();
    }

    @Override
    public AnnouncementDTO create(AnnouncementRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        Announcement entity = new Announcement();
        entity.setTenantId(tenantId);
        entity.setTitle(request.getTitle());
        entity.setContent(request.getContent());
        entity.setPriority(request.getPriority() != null ? request.getPriority() : PRIORITY_NORMAL);
        entity.setStatus(request.getStatus() != null ? request.getStatus() : STATUS_DRAFT);
        entity.setPinned(request.getPinned() != null ? request.getPinned() : false);
        entity.setExpiresAt(request.getExpiresAt());
        entity.setDeletedFlag(false);

        if (STATUS_ACTIVE.equals(entity.getStatus())) {
            entity.setPublishedBy(userId);
            entity.setPublishedAt(Instant.now());
        }

        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());

        announcementMapper.insert(entity);
        return toDTO(entity);
    }

    @Override
    public AnnouncementDTO update(Long id, AnnouncementRequest request) {
        Announcement entity = announcementMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Announcement not found: " + id);
        }

        if (request.getTitle() != null) {
            entity.setTitle(request.getTitle());
        }
        if (request.getContent() != null) {
            entity.setContent(request.getContent());
        }
        if (request.getPriority() != null) {
            entity.setPriority(request.getPriority());
        }
        if (request.getPinned() != null) {
            entity.setPinned(request.getPinned());
        }
        if (request.getExpiresAt() != null) {
            entity.setExpiresAt(request.getExpiresAt());
        }

        // Handle status transition to active
        if (request.getStatus() != null) {
            boolean wasNotActive = !STATUS_ACTIVE.equals(entity.getStatus());
            entity.setStatus(request.getStatus());
            if (wasNotActive && STATUS_ACTIVE.equals(request.getStatus())) {
                entity.setPublishedBy(MetaContext.getCurrentUserId());
                entity.setPublishedAt(Instant.now());
            }
        }

        entity.setUpdatedAt(Instant.now());
        announcementMapper.updateById(entity);
        return toDTO(entity);
    }

    @Override
    public void delete(Long id) {
        Long tenantId = MetaContext.getCurrentTenantId();
        announcementMapper.softDelete(tenantId, id);
    }

    private AnnouncementDTO toDTO(Announcement entity) {
        AnnouncementDTO dto = new AnnouncementDTO();
        dto.setId(entity.getId());
        dto.setTitle(entity.getTitle());
        dto.setContent(entity.getContent());
        dto.setPriority(entity.getPriority());
        dto.setStatus(entity.getStatus());
        dto.setPinned(entity.getPinned());
        dto.setPublishedBy(entity.getPublishedBy());
        dto.setPublishedAt(entity.getPublishedAt());
        dto.setExpiresAt(entity.getExpiresAt());
        dto.setCreatedAt(entity.getCreatedAt());

        if (entity.getPublishedBy() != null) {
            User publisher = userService.findByUserId(entity.getPublishedBy());
            if (publisher != null) {
                dto.setPublishedByName(publisher.getNickName() != null ? publisher.getNickName() : publisher.getUserName());
            }
        }

        return dto;
    }
}
