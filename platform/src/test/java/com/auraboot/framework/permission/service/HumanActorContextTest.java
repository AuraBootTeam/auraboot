package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.mapper.HumanActorMapper;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class HumanActorContextTest {
    final HumanActorMapper mapper = mock(HumanActorMapper.class);
    final RoleMapper roles = mock(RoleMapper.class);
    final HumanActorContext actors = new HumanActorContext(mapper, roles);
    final Map<String,Object> identity = Map.of("user_id", 20L, "user_pid", "human", "member_id", 30L, "username", "operator");
    @BeforeEach void setup() {
        MetaContext.setContext(7L, 99L, "worker", "worker", Set.of(999L));
        MetaContext.setMemberId(100L); MetaContext.setEnvironmentId(8L); MetaContext.setOtelTraceId("original-trace");
        lenient().when(mapper.findActive(eq(7L), any(), any())).thenReturn(List.of(identity));
        var role = new Role(); role.setId(40L);
        lenient().when(roles.findByMemberIdAndTenantId(30L, 7L)).thenReturn(List.of(role));
    }
    @AfterEach void clear() { MetaContext.clear(); }

    @Test void entersTheCurrentHumanWithoutBorrowingWorkerRolesOrCommandAuthorityAndRestoresFailure() {
        var before = MetaContext.snapshot();
        MetaContext.runWithCommandPermitPlan("ALL", 1L, "project", "record", () -> {
            assertThatThrownBy(() -> actors.call(7L, 20L, "human", 30L, () -> {
                assertThat(MetaContext.getCurrentUserId()).isEqualTo(20L);
                assertThat(MetaContext.getCurrentMemberId()).isEqualTo(30L);
                assertThat(MetaContext.getCurrentRoleIds()).containsExactly(40L);
                assertThat(MetaContext.hasCommandPermitScope()).isFalse();
                assertThat(MetaContext.getCurrentEnvironmentId()).isEqualTo(8L);
                throw new IllegalStateException("action failed");
            })).isInstanceOf(IllegalStateException.class).hasMessage("action failed");
            assertThat(MetaContext.hasCommandPermitScopeFor("project")).isTrue();
            assertThat(MetaContext.snapshot()).isEqualTo(before);
        });
        assertThat(MetaContext.snapshot()).isEqualTo(before);
    }

    @Test void missingOrAmbiguousActivePrincipalNeverInvokesTheAction() {
        var action = mock(Runnable.class);
        when(mapper.findActive(7L, 20L, null)).thenReturn(List.of(), List.of(identity, identity));
        assertThatThrownBy(() -> actors.run(7L, 20L, null, null, action)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> actors.run(7L, 20L, null, null, action)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(action);
        assertThat(MetaContext.getCurrentUserId()).isEqualTo(99L);
    }

    @Test void aReplacedMembershipOrMismatchedUserPidCannotResurrectCapturedAuthority() {
        assertThatThrownBy(() -> actors.call(7L, 20L, "human", 31L, () -> "forbidden"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> actors.call(7L, 21L, "human", 30L, () -> "forbidden"))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(roles);
    }

    @Test void roleWithdrawalIsRehydratedRatherThanCopiedFromTheThread() {
        assertThat(actors.call(7L, 20L, null, null, MetaContext::getCurrentRoleIds)).containsExactly(40L);
        when(roles.findByMemberIdAndTenantId(30L, 7L)).thenReturn(List.of());
        assertThat(actors.call(7L, 20L, null, null, MetaContext::getCurrentRoleIds)).isEmpty();
        assertThat(MetaContext.getCurrentRoleIds()).containsExactly(999L);
    }

    @Test void storageFailuresPropagateWithoutAnIdentityFallback() {
        when(mapper.findActive(7L, 20L, null)).thenThrow(new IllegalStateException("storage unavailable"));
        assertThatThrownBy(() -> actors.call(7L, 20L, null, null, () -> "forbidden"))
                .isInstanceOf(IllegalStateException.class).hasMessage("storage unavailable");
        assertThat(MetaContext.getCurrentUserId()).isEqualTo(99L);
    }
}
