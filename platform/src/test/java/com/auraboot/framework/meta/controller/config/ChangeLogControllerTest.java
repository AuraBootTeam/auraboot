package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.entity.DataChangeLog;
import com.auraboot.framework.meta.service.ChangeLogService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChangeLogControllerTest {

    @Test
    void getHistoryResolvesActorDisplayNames() {
        ChangeLogService changeLogService = mock(ChangeLogService.class);
        UserService userService = mock(UserService.class);
        ChangeLogController controller = new ChangeLogController(changeLogService, userService);

        DataChangeLog log = new DataChangeLog();
        log.setId(1L);
        log.setModelCode("e2et_crm_opp");
        log.setChangedBy(200L);
        when(changeLogService.getHistory("e2et_crm_opp", "01KREC")).thenReturn(List.of(log));

        User actor = new User();
        actor.setId(200L);
        actor.setNickName("E2E Test Admin");
        actor.setEmail("e2e@test.local");
        when(userService.findByUserIds(Set.of(200L))).thenReturn(List.of(actor));

        ApiResponse<List<DataChangeLog>> response = controller.getHistory("e2et_crm_opp", "01KREC");

        assertEquals("E2E Test Admin", response.getData().get(0).getChangedByName());
        verify(userService).findByUserIds(Set.of(200L));
    }

    @Test
    void getHistoryPrefersNickNameThenUserName() {
        ChangeLogService changeLogService = mock(ChangeLogService.class);
        UserService userService = mock(UserService.class);
        ChangeLogController controller = new ChangeLogController(changeLogService, userService);

        DataChangeLog nickLog = new DataChangeLog();
        nickLog.setChangedBy(1L);
        DataChangeLog userNameLog = new DataChangeLog();
        userNameLog.setChangedBy(2L);
        when(changeLogService.getHistory("m", "p")).thenReturn(List.of(nickLog, userNameLog));

        User withNick = new User();
        withNick.setId(1L);
        withNick.setNickName("昵称");
        withNick.setUserName("fallback_name");
        User nameOnly = new User();
        nameOnly.setId(2L);
        nameOnly.setUserName("fallback_name");
        when(userService.findByUserIds(Set.of(1L, 2L))).thenReturn(List.of(withNick, nameOnly));

        ApiResponse<List<DataChangeLog>> response = controller.getHistory("m", "p");

        assertEquals("昵称", response.getData().get(0).getChangedByName());
        assertEquals("fallback_name", response.getData().get(1).getChangedByName());
    }

    @Test
    void getHistoryToleratesUnknownActorIdsAndEmptyHistory() {
        ChangeLogService changeLogService = mock(ChangeLogService.class);
        UserService userService = mock(UserService.class);
        ChangeLogController controller = new ChangeLogController(changeLogService, userService);

        when(changeLogService.getHistory("m", "p")).thenReturn(List.of());
        assertEquals(0, controller.getHistory("m", "p").getData().size());
        verify(userService, never()).findByUserIds(any());

        DataChangeLog ghost = new DataChangeLog();
        ghost.setChangedBy(404L);
        when(changeLogService.getHistory("m2", "p2")).thenReturn(List.of(ghost));
        when(userService.findByUserIds(Set.of(404L))).thenReturn(List.of());

        ApiResponse<List<DataChangeLog>> response = controller.getHistory("m2", "p2");
        assertNull(response.getData().get(0).getChangedByName());
    }
}
