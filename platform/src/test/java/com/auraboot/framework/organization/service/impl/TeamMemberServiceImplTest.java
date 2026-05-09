package com.auraboot.framework.organization.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.organization.dto.TeamMemberAddRequest;
import com.auraboot.framework.organization.dto.TeamMemberResponse;
import com.auraboot.framework.organization.entity.Team;
import com.auraboot.framework.organization.entity.TeamMember;
import com.auraboot.framework.organization.mapper.TeamMapper;
import com.auraboot.framework.organization.mapper.TeamMemberMapper;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TeamMemberServiceImpl")
class TeamMemberServiceImplTest {

    @Mock private TeamMapper teamMapper;
    @Mock private TeamMemberMapper teamMemberMapper;
    @Mock private UserService userService;

    private TeamMemberServiceImpl service;
    private TeamMemberServiceImpl spyService;

    @BeforeEach
    void setUp() throws Exception {
        service = new TeamMemberServiceImpl();
        injectField(service, "baseMapper", teamMemberMapper);
        injectField(service, "teamMemberMapper", teamMemberMapper);
        injectField(service, "teamMapper", teamMapper);
        injectField(service, "userService", userService);
        spyService = spy(service);
        MetaContext.setContext(1L, 5L, "user-pid", "alice");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
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

    private Team team(Long id, String pid) {
        Team t = new Team();
        t.setId(id);
        t.setPid(pid);
        t.setCode("c");
        t.setName("n");
        return t;
    }

    @Test
    @DisplayName("listMembers throws when team missing")
    void listMembersTeamMissing() {
        when(teamMapper.findByPid("missing")).thenReturn(null);
        assertThrows(BusinessException.class, () -> spyService.listMembers("missing"));
    }

    @Test
    @DisplayName("listMembers returns mapped responses with user name fallback")
    void listMembers() {
        when(teamMapper.findByPid("p1")).thenReturn(team(1L, "p1"));
        TeamMember m = new TeamMember();
        m.setPid("mp1");
        m.setUserId(7L);
        m.setRole("member");
        when(teamMemberMapper.findByTeamId(1L)).thenReturn(List.of(m));
        User u = new User();
        u.setUserName("bob");
        u.setEmail("bob@x");
        when(userService.findByUserId(7L)).thenReturn(u);

        List<TeamMemberResponse> result = spyService.listMembers("p1");
        assertEquals("bob", result.get(0).getUserName());
        assertEquals("bob@x", result.get(0).getUserEmail());
    }

    @Test
    @DisplayName("listMembers swallows user lookup exception")
    void listMembersUserLookupFails() {
        when(teamMapper.findByPid("p1")).thenReturn(team(1L, "p1"));
        TeamMember m = new TeamMember();
        m.setUserId(7L);
        when(teamMemberMapper.findByTeamId(1L)).thenReturn(List.of(m));
        when(userService.findByUserId(7L)).thenThrow(new RuntimeException("x"));
        List<TeamMemberResponse> result = spyService.listMembers("p1");
        assertNull(result.get(0).getUserName());
    }

    @Test
    @DisplayName("addMember happy path")
    void addMemberHappy() {
        when(teamMapper.findByPid("p1")).thenReturn(team(1L, "p1"));
        when(teamMemberMapper.findByTeamIdAndUserId(1L, 7L)).thenReturn(null);
        User u = new User();
        u.setNickName("Alice");
        when(userService.findByUserId(7L)).thenReturn(u);
        doReturn(true).when(spyService).save(any(TeamMember.class));

        TeamMemberAddRequest req = new TeamMemberAddRequest();
        req.setUserId(7L);
        req.setRole("LEADER");

        TeamMemberResponse resp = spyService.addMember("p1", req, 5L);
        assertEquals("Alice", resp.getUserName());
        assertEquals("LEADER", resp.getRole());
    }

    @Test
    @DisplayName("addMember defaults role to 'member' when null")
    void addMemberDefaultRole() {
        when(teamMapper.findByPid("p1")).thenReturn(team(1L, "p1"));
        when(teamMemberMapper.findByTeamIdAndUserId(1L, 7L)).thenReturn(null);
        when(userService.findByUserId(7L)).thenReturn(new User());
        doReturn(true).when(spyService).save(any(TeamMember.class));

        TeamMemberAddRequest req = new TeamMemberAddRequest();
        req.setUserId(7L);
        req.setRole(null);

        TeamMemberResponse resp = spyService.addMember("p1", req, 5L);
        assertEquals("member", resp.getRole());
    }

    @Test
    @DisplayName("addMember rejects when team missing / already member / user missing")
    void addMemberRejections() {
        TeamMemberAddRequest req = new TeamMemberAddRequest();
        req.setUserId(7L);

        when(teamMapper.findByPid("missing")).thenReturn(null);
        assertThrows(BusinessException.class, () -> spyService.addMember("missing", req, 5L));

        when(teamMapper.findByPid("p1")).thenReturn(team(1L, "p1"));
        when(teamMemberMapper.findByTeamIdAndUserId(1L, 7L)).thenReturn(new TeamMember());
        assertThrows(BusinessException.class, () -> spyService.addMember("p1", req, 5L));

        when(teamMemberMapper.findByTeamIdAndUserId(1L, 7L)).thenReturn(null);
        when(userService.findByUserId(7L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> spyService.addMember("p1", req, 5L));
    }

    @Test
    @DisplayName("removeMember succeeds")
    void removeMember() {
        when(teamMapper.findByPid("p1")).thenReturn(team(1L, "p1"));
        TeamMember m = new TeamMember();
        m.setId(99L);
        doReturn(m).when(spyService).getOne(any(QueryWrapper.class));
        doReturn(true).when(spyService).removeById(anyLong());

        spyService.removeMember("p1", "mp1");
        verify(spyService).removeById(99L);
    }

    @Test
    @DisplayName("removeMember rejects when team or member missing")
    void removeMemberRejections() {
        when(teamMapper.findByPid("missing")).thenReturn(null);
        assertThrows(BusinessException.class, () -> spyService.removeMember("missing", "x"));

        when(teamMapper.findByPid("p1")).thenReturn(team(1L, "p1"));
        doReturn(null).when(spyService).getOne(any(QueryWrapper.class));
        assertThrows(BusinessException.class, () -> spyService.removeMember("p1", "missing"));
    }

    @Test
    @DisplayName("getTeamPidsByUserId empty short-circuits")
    void getTeamPidsEmpty() {
        when(teamMemberMapper.findTeamIdsByUserIdAndTenantId(7L, 1L)).thenReturn(List.of());
        assertTrue(spyService.getTeamPidsByUserId(7L, 1L).isEmpty());
    }

    @Test
    @DisplayName("getTeamPidsByUserId returns selected pids")
    void getTeamPids() {
        when(teamMemberMapper.findTeamIdsByUserIdAndTenantId(7L, 1L)).thenReturn(List.of(1L, 2L));
        when(teamMapper.selectList(any())).thenReturn(List.of(team(1L, "p1"), team(2L, "p2")));
        List<String> pids = spyService.getTeamPidsByUserId(7L, 1L);
        assertEquals(List.of("p1", "p2"), pids);
    }

    @Test
    @DisplayName("getTeamMembershipsByUserId empty short-circuits")
    void getMembershipsEmpty() {
        when(teamMemberMapper.findTeamIdsByUserIdAndTenantId(7L, 1L)).thenReturn(List.of());
        assertTrue(spyService.getTeamMembershipsByUserId(7L, 1L).isEmpty());
    }

    @Test
    @DisplayName("getTeamMembershipsByUserId returns aggregate info")
    void getMemberships() {
        when(teamMemberMapper.findTeamIdsByUserIdAndTenantId(7L, 1L)).thenReturn(List.of(1L));
        when(teamMapper.selectList(any())).thenReturn(List.of(team(1L, "p1")));
        TeamMember m = new TeamMember();
        m.setRole("LEADER");
        when(teamMemberMapper.findByTeamIdAndUserId(1L, 7L)).thenReturn(m);

        List<Map<String, Object>> result = spyService.getTeamMembershipsByUserId(7L, 1L);
        assertEquals("LEADER", result.get(0).get("role"));
        assertEquals("p1", result.get(0).get("teamPid"));
    }

    @Test
    @DisplayName("getTeamMembershipsByUserId defaults role when no member record")
    void getMembershipsNoMember() {
        when(teamMemberMapper.findTeamIdsByUserIdAndTenantId(7L, 1L)).thenReturn(List.of(1L));
        when(teamMapper.selectList(any())).thenReturn(List.of(team(1L, "p1")));
        when(teamMemberMapper.findByTeamIdAndUserId(1L, 7L)).thenReturn(null);

        List<Map<String, Object>> result = spyService.getTeamMembershipsByUserId(7L, 1L);
        assertEquals("member", result.get(0).get("role"));
    }
}
