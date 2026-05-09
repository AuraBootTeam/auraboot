package com.auraboot.framework.workbench.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.framework.workbench.dto.AnnouncementDTO;
import com.auraboot.framework.workbench.dto.AnnouncementRequest;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.AnnouncementMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnnouncementServiceImplTest {

    @Mock private AnnouncementMapper announcementMapper;
    @Mock private UserService userService;
    @InjectMocks private AnnouncementServiceImpl service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(1L, 100L, "user-pid", "tester");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private Announcement entity(Long id, String status) {
        Announcement a = new Announcement();
        a.setId(id);
        a.setTitle("t");
        a.setContent("c");
        a.setPriority("normal");
        a.setStatus(status);
        a.setPinned(false);
        return a;
    }

    @Test
    void listActive_mapsEntitiesToDtos() {
        Announcement a = entity(10L, "active");
        a.setPublishedBy(99L);
        when(announcementMapper.findByStatus(1L, "active", 5)).thenReturn(List.of(a));
        User u = new User();
        u.setNickName("Alice");
        u.setUserName("alice");
        when(userService.findByUserId(99L)).thenReturn(u);

        List<AnnouncementDTO> result = service.listActive(5);

        assertEquals(1, result.size());
        assertEquals("Alice", result.get(0).getPublishedByName());
    }

    @Test
    void listActive_publishedByNameFallsBackToUserName() {
        Announcement a = entity(10L, "active");
        a.setPublishedBy(99L);
        when(announcementMapper.findByStatus(eq(1L), eq("active"), eq(5))).thenReturn(List.of(a));
        User u = new User();
        u.setUserName("bob");
        when(userService.findByUserId(99L)).thenReturn(u);

        List<AnnouncementDTO> result = service.listActive(5);
        assertEquals("bob", result.get(0).getPublishedByName());
    }

    @Test
    void listActive_skipsPublisherLookupWhenNull() {
        Announcement a = entity(10L, "active");
        when(announcementMapper.findByStatus(eq(1L), eq("active"), eq(5))).thenReturn(List.of(a));

        List<AnnouncementDTO> result = service.listActive(5);
        assertNull(result.get(0).getPublishedByName());
        verifyNoInteractions(userService);
    }

    @Test
    void create_appliesDefaultsAndPublishesWhenActive() {
        AnnouncementRequest req = new AnnouncementRequest();
        req.setTitle("Title");
        req.setContent("Body");
        req.setStatus("active");

        AnnouncementDTO dto = service.create(req);

        assertEquals("normal", dto.getPriority());
        assertEquals("active", dto.getStatus());
        assertFalse(dto.getPinned());
        verify(announcementMapper).insert(argThat((Announcement e) -> {
            assertEquals(100L, e.getPublishedBy());
            assertNotNull(e.getPublishedAt());
            assertEquals(1L, e.getTenantId());
            assertFalse(e.getDeletedFlag());
            return true;
        }));
    }

    @Test
    void create_draftDoesNotPublish() {
        AnnouncementRequest req = new AnnouncementRequest();
        req.setTitle("t");
        req.setContent("c");
        req.setPriority("high");
        req.setPinned(true);
        req.setExpiresAt(Instant.now());

        service.create(req);

        verify(announcementMapper).insert(argThat((Announcement e) -> {
            assertEquals("draft", e.getStatus());
            assertNull(e.getPublishedBy());
            assertEquals("high", e.getPriority());
            assertTrue(e.getPinned());
            return true;
        }));
    }

    @Test
    void update_throwsWhenNotFound() {
        when(announcementMapper.selectById(9L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class,
                () -> service.update(9L, new AnnouncementRequest()));
    }

    @Test
    void update_appliesPartialFieldsAndPublishesOnTransition() {
        Announcement existing = entity(9L, "draft");
        when(announcementMapper.selectById(9L)).thenReturn(existing);

        AnnouncementRequest req = new AnnouncementRequest();
        req.setTitle("new-title");
        req.setContent("new-content");
        req.setPriority("high");
        req.setPinned(true);
        req.setExpiresAt(Instant.now());
        req.setStatus("active");

        AnnouncementDTO dto = service.update(9L, req);

        assertEquals("active", dto.getStatus());
        assertEquals("new-title", dto.getTitle());
        assertEquals(100L, dto.getPublishedBy());
        verify(announcementMapper).updateById(existing);
    }

    @Test
    void update_alreadyActiveDoesNotRepublish() {
        Announcement existing = entity(9L, "active");
        existing.setPublishedBy(50L);
        when(announcementMapper.selectById(9L)).thenReturn(existing);

        AnnouncementRequest req = new AnnouncementRequest();
        req.setStatus("active");

        service.update(9L, req);

        // publishedBy not overwritten
        assertEquals(50L, existing.getPublishedBy());
    }

    @Test
    void update_emptyRequestKeepsValues() {
        Announcement existing = entity(9L, "draft");
        when(announcementMapper.selectById(9L)).thenReturn(existing);

        service.update(9L, new AnnouncementRequest());
        verify(announcementMapper).updateById(existing);
        assertEquals("draft", existing.getStatus());
    }

    @Test
    void delete_callsSoftDeleteWithTenantId() {
        service.delete(42L);
        verify(announcementMapper).softDelete(1L, 42L);
    }
}
