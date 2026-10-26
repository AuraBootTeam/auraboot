package com.auraboot.framework.workbench.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.framework.workbench.dto.AnnouncementDTO;
import com.auraboot.framework.workbench.dto.AnnouncementRequest;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.AnnouncementLifecycleMapper;
import com.auraboot.framework.workbench.service.AnnouncementService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.List;

/** Recipient reads use sealed recipient facts; legacy writes cannot bypass the command pipeline. */
@Service
@RequiredArgsConstructor
public class AnnouncementServiceImpl implements AnnouncementService {
    private final AnnouncementLifecycleMapper mapper;
    private final UserService userService;

    @Override
    public List<AnnouncementDTO> listActive(int limit) {
        if (limit < 1 || limit > 100)
            throw BusinessException.i18n("announcement.error.invalid_input");
        return mapper
                .listVisible(
                        MetaContext.getCurrentTenantId(),
                        MetaContext.getCurrentUserId(),
                        MetaContext.getCurrentUserPid(),
                        limit)
                .stream()
                .map(this::toDTO)
                .toList();
    }

    @Override
    public AnnouncementDTO create(AnnouncementRequest request) {
        throw BusinessException.i18n("announcement.error.command_required");
    }

    @Override
    public AnnouncementDTO update(Long id, AnnouncementRequest request) {
        throw BusinessException.i18n("announcement.error.command_required");
    }

    @Override
    public void delete(Long id) {
        throw BusinessException.i18n("announcement.error.command_required");
    }

    private AnnouncementDTO toDTO(Announcement entity) {
        AnnouncementDTO dto = new AnnouncementDTO();
        dto.setPid(entity.getPid());
        dto.setId(entity.getPid());
        dto.setTitle(entity.getTitle());
        dto.setContent(entity.getContent());
        dto.setPriority(entity.getAnnouncementPriority());
        dto.setStatus(entity.getStatus());
        dto.setPinned(entity.getPinned());
        dto.setPublishedAt(entity.getPublishedAt());
        dto.setExpiresAt(entity.getExpiresAt());
        dto.setCreatedAt(entity.getCreatedAt());
        if (entity.getPublishedBy() != null) {
            User publisher = userService.findByUserId(entity.getPublishedBy());
            if (publisher != null) {
                dto.setPublishedBy(publisher.getPid());
                dto.setPublishedByName(
                        publisher.getNickName() == null || publisher.getNickName().isBlank()
                                ? publisher.getUserName()
                                : publisher.getNickName());
            }
        }
        return dto;
    }
}
