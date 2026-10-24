package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.workbench.mapper.AnnouncementReleaseMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnnouncementActorContextTest {
    private final AnnouncementReleaseMapper actors=mock(AnnouncementReleaseMapper.class);
    private final AnnouncementActorContext context=new AnnouncementActorContext(actors);
    @AfterEach void clear(){MetaContext.clear();}
    @Test void originalMemberReceivesFreshRolesButNoRegistryBypassAndTheCallerIsRestored() {
        when(actors.actor(1L,2L)).thenReturn(Map.of("member_pid","original","member_id",3L,"user_pid","user","username","User"));
        when(actors.actorRoles(1L,3L)).thenReturn(Set.of(4L));
        MetaContext.setContext(9L,8L,"outer","Outer",Set.of(10L));MetaContext.setMemberId(7L);
        String result=MetaContext.runWithoutTenantFilter(()->{
            String value=context.call(1L,2L,"original",()->{
                assertThat(MetaContext.getCurrentTenantId()).isEqualTo(1L);assertThat(MetaContext.getCurrentUserId()).isEqualTo(2L);
                assertThat(MetaContext.getCurrentMemberId()).isEqualTo(3L);assertThat(MetaContext.getCurrentRoleIds()).containsExactly(4L);
                assertThat(MetaContext.isTenantFilterBypassed()).isFalse();return "authorized";
            });
            assertThat(MetaContext.isTenantFilterBypassed()).isTrue();return value;
        });
        assertThat(result).isEqualTo("authorized");assertThat(MetaContext.getCurrentTenantId()).isEqualTo(9L);
        assertThat(MetaContext.getCurrentMemberId()).isEqualTo(7L);assertThat(MetaContext.getCurrentRoleIds()).containsExactly(10L);
        assertThat(MetaContext.isTenantFilterBypassed()).isFalse();
    }
    @Test void replacementMembershipCannotRunTheOldActorsBusinessAction() {
        when(actors.actor(1L,2L)).thenReturn(Map.of("member_pid","replacement","member_id",3L,"user_pid","user","username","User"));
        var ran=new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(()->context.call(1L,2L,"original",()->{ran.set(true);return null;}))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(ran).isFalse();verify(actors,never()).actorRoles(any(),any());assertThat(MetaContext.exists()).isFalse();
    }
    @Test void failedBusinessAuthorizationDoesNotLeakTheBackgroundIdentity() {
        when(actors.actor(1L,2L)).thenReturn(Map.of("member_pid","original","member_id",3L,"user_pid","user","username","User"));
        when(actors.actorRoles(1L,3L)).thenReturn(Set.of(4L));
        assertThatThrownBy(()->context.call(1L,2L,"original",()->{throw new org.springframework.security.access.AccessDeniedException("revoked");}))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class).hasMessage("revoked");
        assertThat(MetaContext.exists()).isFalse();assertThat(MetaContext.isTenantFilterBypassed()).isFalse();
    }
    @Test void nullableDisplayUsernameDoesNotInvalidatePersistedUserAndMemberIdentity() {
        when(actors.actor(1L,2L)).thenReturn(Map.of("member_pid","original","member_id",3L,"user_pid","user"));
        when(actors.actorRoles(1L,3L)).thenReturn(Set.of(4L));
        assertThat(context.call(1L,2L,"original",()->{
            assertThat(MetaContext.getCurrentUserId()).isEqualTo(2L);
            assertThat(MetaContext.getCurrentMemberId()).isEqualTo(3L);
            return MetaContext.getCurrentUserPid();
        })).isEqualTo("user");
        assertThat(MetaContext.exists()).isFalse();
    }
}
