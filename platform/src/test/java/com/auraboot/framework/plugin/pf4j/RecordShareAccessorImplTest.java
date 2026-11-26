package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.entity.RecordShare;
import com.auraboot.framework.permission.service.RecordShareService;
import com.auraboot.framework.plugin.extension.RecordShareAccessor.Replacement;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.dao.mapper.TenantMemberMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecordShareAccessorImplTest {

    @Mock RecordShareService recordShareService;
    @Mock TenantMemberMapper tenantMemberMapper;

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void replacesMemberSharesUsingTenantResolvedUserPids() {
        TenantMember memberA = member("member-a");
        TenantMember memberB = member("member-b");
        when(tenantMemberMapper.findActiveRecipients(7L, List.of("user-a", "user-b")))
                .thenReturn(List.of(
                        new TenantMemberMapper.ActiveMemberRecipient("user-b", memberB.getPid(), memberB.getId()),
                        new TenantMemberMapper.ActiveMemberRecipient("user-a", memberA.getPid(), memberA.getId())));

        RecordShare retained = share("share-a", "member-a");
        RecordShare removed = share("share-old", "member-old");
        when(recordShareService.listByRecordPidForManagement(7L, "crm_lead_pool_item_common", "item-1"))
                .thenReturn(List.of(retained, removed));

        new RecordShareAccessorImpl(recordShareService, tenantMemberMapper)
                .replaceReadSharesForUsers(7L, "crm_lead_pool_item_common", "item-1",
                        List.of(" user-a ", "user-a", "", "user-b"));
        verify(tenantMemberMapper).findActiveRecipients(7L, List.of("user-a", "user-b"));
        verifyNoMoreInteractions(tenantMemberMapper);

        verify(recordShareService).removeByPids(7L, List.of("share-old"));
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(recordShareService, times(2)).shareRecordByPid(
                eq(7L), eq("crm_lead_pool_item_common"), eq("item-1"), eq("member"),
                anyLong(), subject.capture(), eq("read"), isNull());
        assertEquals(Set.of("member-a", "member-b"), Set.copyOf(subject.getAllValues()));
    }

    @Test
    void rejectsUnknownOrInactiveUserPidInsteadOfLeakingScope() {
        when(tenantMemberMapper.findActiveByTenantIdAndUserPid(7L, "unknown")).thenReturn(null);
        RecordShareAccessorImpl accessor = new RecordShareAccessorImpl(recordShareService, tenantMemberMapper);

        assertThrows(RuntimeException.class, () -> accessor.replaceReadSharesForUsers(
                7L, "crm_lead_pool_common", "pool-1", Set.of("unknown")));
        verifyNoInteractions(recordShareService);
    }

    @Test
    void rejectsAnIncompleteAudienceBeforeReadingOrMutatingShares() {
        when(tenantMemberMapper.findActiveRecipients(7L, List.of("user-a", "foreign")))
                .thenReturn(List.of(new TenantMemberMapper.ActiveMemberRecipient("user-a", "member-a", 1L)));
        RecordShareAccessorImpl accessor = new RecordShareAccessorImpl(recordShareService, tenantMemberMapper);

        assertThrows(com.auraboot.framework.exception.BusinessException.class,
                () -> accessor.replaceReadUpdateSharesForUsers(
                        7L, "crm_lead_pool_item_common", "item-1", List.of("user-a", "foreign")));
        verifyNoInteractions(recordShareService);
    }

    @Test
    void resolvesTheUnionOnceAndKeepsTheAudienceAndMaskOfEachRecord() {
        when(tenantMemberMapper.findActiveRecipients(7L, List.of("owner", "manager")))
                .thenReturn(List.of(new TenantMemberMapper.ActiveMemberRecipient("owner", "member-a", 1L),
                        new TenantMemberMapper.ActiveMemberRecipient("manager", "member-b", 2L)));
        when(recordShareService.listByRecordPidForManagement(eq(7L), anyString(), anyString()))
                .thenReturn(List.of());

        new RecordShareAccessorImpl(recordShareService, tenantMemberMapper)
                .replaceSharesForRecords(7L, List.of(
                        new Replacement("account", "account-1", Set.of("owner"), true),
                        new Replacement("pool_item", "item-1", Set.of("owner"), true),
                        new Replacement("history", "history-1", List.of("owner", "manager"), false)));

        verify(tenantMemberMapper).findActiveRecipients(7L, List.of("owner", "manager"));
        verifyNoMoreInteractions(tenantMemberMapper);
        verify(recordShareService).shareRecordByPid(7L, "account", "account-1",
                "member", 1L, "member-a", "read,update", null);
        verify(recordShareService).shareRecordByPid(7L, "pool_item", "item-1",
                "member", 1L, "member-a", "read,update", null);
        verify(recordShareService).shareRecordByPid(7L, "history", "history-1",
                "member", 1L, "member-a", "read", null);
        verify(recordShareService).shareRecordByPid(7L, "history", "history-1",
                "member", 2L, "member-b", "read", null);
        verify(recordShareService, times(4))
                .shareRecordByPid(anyLong(), anyString(), anyString(), anyString(),
                        anyLong(), anyString(), anyString(), isNull());
    }

    @Test
    void writesReadUpdateMaskForCollaborationShares() {
        TenantMember member = member("member-a");
        when(tenantMemberMapper.findActiveByTenantIdAndUserPid(7L, "user-a")).thenReturn(member);
        when(recordShareService.listByRecordPidForManagement(7L, "crm_lead_pool_item_common", "item-1"))
                .thenReturn(List.of());

        new RecordShareAccessorImpl(recordShareService, tenantMemberMapper)
                .replaceReadUpdateSharesForUsers(
                        7L, "crm_lead_pool_item_common", "item-1", Set.of("user-a"));

        verify(recordShareService).shareRecordByPid(
                7L, "crm_lead_pool_item_common", "item-1", "member",
                member.getId(), "member-a", "read,update", null);
        org.junit.jupiter.api.Assertions.assertFalse(MetaContext.exists());
    }

    @Test
    void backgroundCallBindsSystemContextForShareAudit_thenClearsIt() {
        TenantMember member = member("member-a");
        when(tenantMemberMapper.findActiveByTenantIdAndUserPid(7L, "user-a")).thenReturn(member);
        when(recordShareService.listByRecordPidForManagement(7L, "crm_lead_pool_item_common", "item-1"))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            assertEquals(7L, MetaContext.getCurrentTenantId());
            assertEquals(0L, MetaContext.getCurrentUserId());
            return null;
        }).when(recordShareService).shareRecordByPid(
                anyLong(), anyString(), anyString(), anyString(), anyLong(), anyString(), anyString(), isNull());

        new RecordShareAccessorImpl(recordShareService, tenantMemberMapper)
                .replaceReadUpdateSharesForUsers(
                        7L, "crm_lead_pool_item_common", "item-1", Set.of("user-a"));

        org.junit.jupiter.api.Assertions.assertFalse(MetaContext.exists());
    }

    @Test
    void foregroundCallPreservesMatchingActorContext() {
        MetaContext.setContext(7L, 99L, "user-a", "alice");
        when(recordShareService.listByRecordPidForManagement(7L, "crm_lead_pool_item_common", "item-1"))
                .thenReturn(List.of());

        new RecordShareAccessorImpl(recordShareService, tenantMemberMapper)
                .replaceReadSharesForUsers(7L, "crm_lead_pool_item_common", "item-1", Set.of());

        assertEquals(7L, MetaContext.getCurrentTenantId());
        assertEquals(99L, MetaContext.getCurrentUserId());
    }

    @Test
    void rejectsCrossTenantCallFromExistingContext() {
        MetaContext.setContext(8L, 99L, "user-a", "alice");

        assertThrows(RuntimeException.class, () ->
                new RecordShareAccessorImpl(recordShareService, tenantMemberMapper)
                        .replaceReadSharesForUsers(7L, "crm_lead_pool_item_common", "item-1", Set.of()));
        verifyNoInteractions(recordShareService, tenantMemberMapper);
    }

    private static TenantMember member(String pid) {
        TenantMember member = new TenantMember();
        member.setId((long) Math.abs(pid.hashCode()));
        member.setPid(pid);
        return member;
    }

    private static RecordShare share(String pid, String subjectPid) {
        RecordShare share = new RecordShare();
        share.setPid(pid);
        share.setSubjectType("member");
        share.setSubjectPid(subjectPid);
        return share;
    }
}
