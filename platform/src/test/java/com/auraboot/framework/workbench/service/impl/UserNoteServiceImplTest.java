package com.auraboot.framework.workbench.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.workbench.dto.UserNoteDTO;
import com.auraboot.framework.workbench.entity.UserNote;
import com.auraboot.framework.workbench.mapper.UserNoteMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserNoteServiceImplTest {

    @Mock private UserNoteMapper userNoteMapper;
    @InjectMocks private UserNoteServiceImpl service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(7L, 42L, "u-pid", "u");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void getNote_returnsEmptyDtoWhenAbsent() {
        when(userNoteMapper.findByUser(42L, 7L)).thenReturn(null);
        UserNoteDTO dto = service.getNote();
        assertNotNull(dto);
        assertNull(dto.getContent());
        assertNull(dto.getUpdatedAt());
    }

    @Test
    void getNote_returnsContentAndTimestamp() {
        UserNote note = new UserNote();
        note.setContent("hello");
        Instant now = Instant.now();
        note.setUpdatedAt(now);
        when(userNoteMapper.findByUser(42L, 7L)).thenReturn(note);

        UserNoteDTO dto = service.getNote();
        assertEquals("hello", dto.getContent());
        assertEquals(now, dto.getUpdatedAt());
    }

    @Test
    void upsert_createsWhenAbsent() {
        when(userNoteMapper.findByUser(42L, 7L)).thenReturn(null);
        UserNoteDTO dto = service.upsert("new-content");
        assertEquals("new-content", dto.getContent());
        assertNotNull(dto.getUpdatedAt());
        verify(userNoteMapper).insert(argThat((UserNote n) -> n.getUserId().equals(42L)
                && n.getTenantId().equals(7L)
                && "new-content".equals(n.getContent())
                && n.getCreatedAt() != null && n.getUpdatedAt() != null));
        verify(userNoteMapper, never()).updateById(any(UserNote.class));
    }

    @Test
    void upsert_updatesExisting() {
        UserNote existing = new UserNote();
        existing.setUserId(42L);
        existing.setTenantId(7L);
        existing.setContent("old");
        when(userNoteMapper.findByUser(42L, 7L)).thenReturn(existing);

        UserNoteDTO dto = service.upsert("updated");
        assertEquals("updated", dto.getContent());
        verify(userNoteMapper).updateById(existing);
        verify(userNoteMapper, never()).insert(any(UserNote.class));
        assertEquals("updated", existing.getContent());
    }
}
