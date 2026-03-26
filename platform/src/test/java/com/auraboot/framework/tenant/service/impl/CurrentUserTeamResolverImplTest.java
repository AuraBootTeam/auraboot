package com.auraboot.framework.tenant.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.organization.service.TeamMemberService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CurrentUserTeamResolverImpl")
class CurrentUserTeamResolverImplTest {

    @Mock
    private TeamMemberService teamMemberService;

    private MockedStatic<MetaContext> metaContextMock;

    @AfterEach
    void tearDown() {
        if (metaContextMock != null) {
            metaContextMock.close();
        }
    }

    @Test
    @DisplayName("resolves team PIDs from TeamMemberService")
    void resolvesTeamPidsFromService() {
        metaContextMock = Mockito.mockStatic(MetaContext.class);
        metaContextMock.when(MetaContext::exists).thenReturn(true);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(100L);
        metaContextMock.when(MetaContext::getCurrentUserId).thenReturn(200L);

        when(teamMemberService.getTeamPidsByUserId(200L, 100L))
                .thenReturn(List.of("team_a", "team_b", "team_c"));

        CurrentUserTeamResolverImpl resolver = new CurrentUserTeamResolverImpl(teamMemberService);

        List<String> result = resolver.resolveCurrentUserTeamIds();

        assertEquals(List.of("team_a", "team_b", "team_c"), result);
    }

    @Test
    @DisplayName("returns empty when service returns empty list")
    void returnsEmptyWhenNoTeams() {
        metaContextMock = Mockito.mockStatic(MetaContext.class);
        metaContextMock.when(MetaContext::exists).thenReturn(true);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(100L);
        metaContextMock.when(MetaContext::getCurrentUserId).thenReturn(200L);

        when(teamMemberService.getTeamPidsByUserId(200L, 100L))
                .thenReturn(List.of());

        CurrentUserTeamResolverImpl resolver = new CurrentUserTeamResolverImpl(teamMemberService);

        assertTrue(resolver.resolveCurrentUserTeamIds().isEmpty());
    }

    @Test
    @DisplayName("returns empty when MetaContext is absent")
    void returnsEmptyWhenNoMetaContext() {
        metaContextMock = Mockito.mockStatic(MetaContext.class);
        metaContextMock.when(MetaContext::exists).thenReturn(false);

        CurrentUserTeamResolverImpl resolver = new CurrentUserTeamResolverImpl(teamMemberService);

        assertTrue(resolver.resolveCurrentUserTeamIds().isEmpty());
    }

    @Test
    @DisplayName("returns empty when tenantId is null")
    void returnsEmptyWhenTenantIdNull() {
        metaContextMock = Mockito.mockStatic(MetaContext.class);
        metaContextMock.when(MetaContext::exists).thenReturn(true);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(null);
        metaContextMock.when(MetaContext::getCurrentUserId).thenReturn(200L);

        CurrentUserTeamResolverImpl resolver = new CurrentUserTeamResolverImpl(teamMemberService);

        assertTrue(resolver.resolveCurrentUserTeamIds().isEmpty());
    }
}
