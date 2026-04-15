package com.auraboot.framework.workbench.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.workbench.dto.UserNoteDTO;
import com.auraboot.framework.workbench.entity.UserNote;
import com.auraboot.framework.workbench.mapper.UserNoteMapper;
import com.auraboot.framework.workbench.service.UserNoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Implementation of UserNoteService.
 *
 * @since 6.5.0
 */
@Service
@RequiredArgsConstructor
public class UserNoteServiceImpl implements UserNoteService {

    private final UserNoteMapper userNoteMapper;

    @Override
    public UserNoteDTO getNote() {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        UserNote note = userNoteMapper.findByUser(userId, tenantId);

        UserNoteDTO dto = new UserNoteDTO();
        if (note != null) {
            dto.setContent(note.getContent());
            dto.setUpdatedAt(note.getUpdatedAt());
        }
        return dto;
    }

    @Override
    public UserNoteDTO upsert(String content) {
        Long userId = MetaContext.getCurrentUserId();
        Long tenantId = MetaContext.getCurrentTenantId();
        UserNote existing = userNoteMapper.findByUser(userId, tenantId);

        if (existing != null) {
            existing.setContent(content);
            existing.setUpdatedAt(Instant.now());
            userNoteMapper.updateById(existing);
        } else {
            existing = new UserNote();
            existing.setUserId(userId);
            existing.setTenantId(tenantId);
            existing.setContent(content);
            existing.setCreatedAt(Instant.now());
            existing.setUpdatedAt(Instant.now());
            userNoteMapper.insert(existing);
        }

        UserNoteDTO dto = new UserNoteDTO();
        dto.setContent(existing.getContent());
        dto.setUpdatedAt(existing.getUpdatedAt());
        return dto;
    }
}
