package com.auraboot.framework.workbench.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.framework.workbench.dto.AnnouncementRequest;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.AnnouncementLifecycleMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

class AnnouncementServiceImplTest {
    private final AnnouncementLifecycleMapper mapper = mock(AnnouncementLifecycleMapper.class);
    private final AnnouncementServiceImpl service =
            new AnnouncementServiceImpl(mapper, mock(UserService.class));

    @AfterEach
    void clear() {
        MetaContext.clear();
    }

    @Test
    void recipientReadsUseTrustedIdentityAndPublicPid() {
        MetaContext.setContext(1L, 100L, "user-pid", "tester");
        Announcement entity = new Announcement();
        entity.setId(9007199254740993L);
        entity.setPid("notice-pid");
        entity.setTitle("notice");
        entity.setStatus("published");
        entity.setAnnouncementPriority("important");
        when(mapper.listVisible(1L, 100L, "user-pid", 5)).thenReturn(List.of(entity));
        var result = service.listActive(5);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("notice-pid");
        assertThat(result.get(0).getPriority()).isEqualTo("important");
    }

    @Test
    void legacyServiceCannotPublishOrModify() {
        assertThatThrownBy(() -> service.create(new AnnouncementRequest()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.update(1L, new AnnouncementRequest()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void unboundedReadsAreRejected() {
        assertThatThrownBy(() -> service.listActive(0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.listActive(101)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }
}
