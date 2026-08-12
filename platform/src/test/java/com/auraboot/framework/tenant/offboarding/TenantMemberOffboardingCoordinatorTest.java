package com.auraboot.framework.tenant.offboarding;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantMemberOffboardingCoordinatorTest {

    @Mock private TenantMemberOffboardingHandler handler;
    @Mock private TenantMemberMapper memberMapper;
    @Mock private TenantAdminContinuityGuard adminContinuityGuard;

    private TenantMemberOffboardingCoordinator coordinator;
    private TenantMember source;
    private TenantMember target;

    @BeforeEach
    void setUp() {
        coordinator = new TenantMemberOffboardingCoordinator(
                List.of(handler), memberMapper, adminContinuityGuard);
        source = member(1L, "source", 10L, "active");
        target = member(2L, "target", 20L, "active");
    }

    @Test
    void inspectReturnsAggregatedPidSafeImpact() {
        when(handler.inspect(org.mockito.ArgumentMatchers.any())).thenReturn(
                new TenantMemberOffboardingImpact("qr_code", "QR codes", 3, true));

        var response = coordinator.inspect(source, null, 99L, TenantMemberOffboardingAction.REMOVE);

        assertThat(response.getMemberPid()).isEqualTo("source");
        assertThat(response.getOwnedResourceCount()).isEqualTo(3);
        assertThat(response.isTransferRequired()).isTrue();
        assertThat(response.getResources()).singleElement()
                .extracting("resourceType", "ownedCount", "transferable")
                .containsExactly("qr_code", 3L, true);
    }

    @Test
    void prepareAllowsMemberWithoutOwnedResources() {
        when(handler.inspect(org.mockito.ArgumentMatchers.any())).thenReturn(
                new TenantMemberOffboardingImpact("qr_code", "QR codes", 0, true));

        coordinator.prepare(source, null, 99L, TenantMemberOffboardingAction.REMOVE);

        verify(handler, never()).transfer(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void prepareRequiresTargetForOwnedResources() {
        when(handler.inspect(org.mockito.ArgumentMatchers.any())).thenReturn(
                new TenantMemberOffboardingImpact("qr_code", "QR codes", 1, true));

        assertThatThrownBy(() -> coordinator.prepare(
                source, null, 99L, TenantMemberOffboardingAction.REMOVE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("targetMemberPid");
    }

    @Test
    void prepareTransfersAndRechecksOwnership() {
        when(memberMapper.findByTenantIdAndPid(7L, "target")).thenReturn(target);
        when(handler.inspect(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new TenantMemberOffboardingImpact("qr_code", "QR codes", 2, true))
                .thenReturn(new TenantMemberOffboardingImpact("qr_code", "QR codes", 0, true));

        coordinator.prepare(source, "target", 99L, TenantMemberOffboardingAction.DEACTIVATE);

        verify(handler).transfer(org.mockito.ArgumentMatchers.argThat(context ->
                context.sourceMemberPid().equals("source")
                        && context.targetMemberPid().equals("target")
                        && context.sourceUserId().equals(10L)
                        && context.targetUserId().equals(20L)));
    }

    @Test
    void prepareFailsWhenTransferLeavesOwnedResources() {
        when(memberMapper.findByTenantIdAndPid(7L, "target")).thenReturn(target);
        when(handler.inspect(org.mockito.ArgumentMatchers.any())).thenReturn(
                new TenantMemberOffboardingImpact("qr_code", "QR codes", 2, true));

        assertThatThrownBy(() -> coordinator.prepare(
                source, "target", 99L, TenantMemberOffboardingAction.REMOVE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("left owned resources");
    }

    @Test
    void prepareRejectsInactiveOrSameTarget() {
        target.setStatus("suspended");
        when(memberMapper.findByTenantIdAndPid(7L, "target")).thenReturn(target);
        assertThatThrownBy(() -> coordinator.prepare(
                source, "target", 99L, TenantMemberOffboardingAction.REMOVE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("active in the same tenant");

        when(memberMapper.findByTenantIdAndPid(7L, "source")).thenReturn(source);
        assertThatThrownBy(() -> coordinator.prepare(
                source, "source", 99L, TenantMemberOffboardingAction.REMOVE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("must differ");
    }

    private TenantMember member(Long id, String pid, Long userId, String status) {
        TenantMember member = new TenantMember();
        member.setId(id);
        member.setPid(pid);
        member.setTenantId(7L);
        member.setUserId(userId);
        member.setStatus(status);
        return member;
    }

}
