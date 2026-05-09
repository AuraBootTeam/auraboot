package com.auraboot.framework.organization.service.impl;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.organization.dto.*;
import com.auraboot.framework.organization.entity.Team;
import com.auraboot.framework.organization.entity.TeamMember;
import com.auraboot.framework.organization.mapper.TeamMapper;
import com.auraboot.framework.organization.mapper.TeamMemberMapper;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TeamServiceImpl")
class TeamServiceImplTest {

    @Mock private TeamMapper teamMapper;
    @Mock private TeamMemberMapper teamMemberMapper;
    @Mock private UserService userService;

    private TeamServiceImpl service;
    private TeamServiceImpl spyService;

    @BeforeEach
    void setUp() throws Exception {
        service = new TeamServiceImpl();
        injectField(service, "baseMapper", teamMapper);
        injectField(service, "teamMapper", teamMapper);
        injectField(service, "teamMemberMapper", teamMemberMapper);
        injectField(service, "userService", userService);
        spyService = spy(service);
    }

    static void injectField(Object target, String name, Object value) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private Team team(Long id, String pid, String code) {
        Team t = new Team();
        t.setId(id);
        t.setPid(pid);
        t.setCode(code);
        t.setName("name-" + code);
        t.setLeaderId("leader-pid");
        return t;
    }

    @Test
    @DisplayName("listTeams maps to responses with member counts")
    void listTeams() {
        Team t = team(1L, "p1", "c1");
        doReturn(List.of(t)).when(spyService).list(any(QueryWrapper.class));
        when(teamMemberMapper.selectCount(any())).thenReturn(3L);
        when(userService.findByPid("leader-pid")).thenReturn(null);

        List<TeamResponse> result = spyService.listTeams(1L);
        assertEquals(1, result.size());
        assertEquals(3, result.get(0).getMemberCount());
    }

    @Test
    @DisplayName("getTeamByPid throws when not found")
    void getTeamByPidNotFound() {
        when(teamMapper.findByPid("missing")).thenReturn(null);
        assertThrows(BusinessException.class, () -> spyService.getTeamByPid("missing"));
    }

    @Test
    @DisplayName("getTeamByPid resolves leader name from nickName")
    void getTeamByPidWithLeader() {
        Team t = team(1L, "p1", "c1");
        when(teamMapper.findByPid("p1")).thenReturn(t);
        User u = new User();
        u.setNickName("Alice");
        u.setUserName("alice");
        when(userService.findByPid("leader-pid")).thenReturn(u);
        when(teamMemberMapper.selectCount(any())).thenReturn(0L);

        TeamResponse resp = spyService.getTeamByPid("p1");
        assertEquals("Alice", resp.getLeaderName());
        assertEquals(0, resp.getMemberCount());
    }

    @Test
    @DisplayName("getTeamByPid leader resolution fallback to userName + exception swallow")
    void getTeamByPidLeaderFallback() {
        Team t = team(1L, "p1", "c1");
        when(teamMapper.findByPid("p1")).thenReturn(t);
        User u = new User();
        u.setUserName("bob");
        when(userService.findByPid("leader-pid")).thenReturn(u);
        when(teamMemberMapper.selectCount(any())).thenReturn(null);
        assertEquals("bob", spyService.getTeamByPid("p1").getLeaderName());

        when(userService.findByPid("leader-pid")).thenThrow(new RuntimeException("boom"));
        assertNull(spyService.getTeamByPid("p1").getLeaderName());
    }

    @Test
    @DisplayName("createTeam fails when code exists")
    void createTeamDuplicate() {
        TeamCreateRequest req = new TeamCreateRequest();
        req.setCode("c1");
        req.setName("n");
        when(teamMapper.findByTenantIdAndCode(1L, "c1")).thenReturn(team(99L, "x", "c1"));
        assertThrows(BusinessException.class, () -> spyService.createTeam(req, 1L, 5L));
    }

    @Test
    @DisplayName("createTeam saves and maps response")
    void createTeamHappy() {
        TeamCreateRequest req = new TeamCreateRequest();
        req.setCode("c1");
        req.setName("Team");
        req.setDescription("d");
        req.setLeaderId(null);
        when(teamMapper.findByTenantIdAndCode(1L, "c1")).thenReturn(null);
        doReturn(true).when(spyService).save(any(Team.class));
        when(teamMemberMapper.selectCount(any())).thenReturn(0L);

        TeamResponse resp = spyService.createTeam(req, 1L, 5L);
        assertEquals("c1", resp.getCode());
        verify(spyService).save(any(Team.class));
    }

    @Test
    @DisplayName("updateTeam updates only set fields")
    void updateTeam() {
        Team t = team(1L, "p1", "c1");
        when(teamMapper.findByPid("p1")).thenReturn(t);
        TeamUpdateRequest req = new TeamUpdateRequest();
        req.setName("New");
        req.setDescription("desc");
        req.setLeaderId("L2");
        req.setStatus("ACTIVE");
        doReturn(true).when(spyService).updateById(any(Team.class));
        when(teamMemberMapper.selectCount(any())).thenReturn(0L);
        when(userService.findByPid("L2")).thenReturn(null);

        TeamResponse resp = spyService.updateTeam("p1", req, 9L);
        assertEquals("New", resp.getName());
    }

    @Test
    @DisplayName("updateTeam fails when not found")
    void updateTeamNotFound() {
        when(teamMapper.findByPid("missing")).thenReturn(null);
        assertThrows(BusinessException.class,
            () -> spyService.updateTeam("missing", new TeamUpdateRequest(), 1L));
    }

    @Test
    @DisplayName("deleteTeam removes members and team")
    void deleteTeam() {
        Team t = team(1L, "p1", "c1");
        when(teamMapper.findByPid("p1")).thenReturn(t);
        when(teamMemberMapper.delete(any())).thenReturn(2);
        doReturn(true).when(spyService).removeById(anyLong());

        spyService.deleteTeam("p1");
        verify(teamMemberMapper).delete(any());
        verify(spyService).removeById(1L);
    }

    @Test
    @DisplayName("deleteTeam fails when not found")
    void deleteTeamNotFound() {
        when(teamMapper.findByPid("missing")).thenReturn(null);
        assertThrows(BusinessException.class, () -> spyService.deleteTeam("missing"));
    }

    @Test
    @DisplayName("getCurrentUserTeams returns empty when no memberships")
    void getCurrentUserTeamsEmpty() {
        when(teamMemberMapper.findByUserIdAndTenantId(7L, 1L)).thenReturn(List.of());
        assertTrue(spyService.getCurrentUserTeams(7L, 1L).isEmpty());
    }

    @Test
    @DisplayName("getCurrentUserTeams returns mapped teams")
    void getCurrentUserTeams() {
        TeamMember m = new TeamMember();
        m.setTeamId(1L);
        when(teamMemberMapper.findByUserIdAndTenantId(7L, 1L)).thenReturn(List.of(m));
        Team t = team(1L, "p1", "c1");
        doReturn(List.of(t)).when(spyService).list(any(QueryWrapper.class));
        when(teamMemberMapper.selectCount(any())).thenReturn(1L);
        when(userService.findByPid("leader-pid")).thenReturn(null);

        List<TeamResponse> result = spyService.getCurrentUserTeams(7L, 1L);
        assertEquals(1, result.size());
    }
}
