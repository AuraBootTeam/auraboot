package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.extension.IdentityDirectoryAccessor;
import com.auraboot.framework.workbench.mapper.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AnnouncementOperatingPolicyServiceTest {
    private final AnnouncementTenantPolicyService policies=mock(AnnouncementTenantPolicyService.class);
    private final AnnouncementOperatingPolicyMapper store=mock(AnnouncementOperatingPolicyMapper.class);
    private final AnnouncementLifecycleMapper operations=mock(AnnouncementLifecycleMapper.class);
    private final AnnouncementAccessService access=mock(AnnouncementAccessService.class);
    private final IdentityDirectoryAccessor identities=mock(IdentityDirectoryAccessor.class);
    private final AnnouncementApprovalCatalog catalog=mock(AnnouncementApprovalCatalog.class);
    private final AnnouncementCodec codec=new AnnouncementCodec(new ObjectMapper());
    private final AnnouncementOperatingPolicyService service=new AnnouncementOperatingPolicyService(policies,store,operations,access,identities,catalog,codec);
    @BeforeEach void setup() {
        MetaContext.setContext(1L,7L,"actor","Actor");MetaContext.setMemberId(70L);
        when(access.actor("policy_manage")).thenReturn(7L);
        when(identities.findEnabledTenantMembership(1L,"actor")).thenReturn(new IdentityDirectoryAccessor.TenantUserIdentity(
            new IdentityDirectoryAccessor.UserIdentity(7L,"actor","actor","Actor"),"membership",70L));
        when(operations.claim(anyLong(),anyLong(),anyString(),anyString(),anyString())).thenReturn(1);
        when(operations.finish(anyLong(),anyLong(),anyString(),anyString(),isNull(),anyString())).thenReturn(1);
        when(store.lock(1L)).thenReturn(null);
        when(store.insert(anyLong(),anyLong(),anyMap())).thenReturn(1);
        when(store.update(anyLong(),anyLong(),anyInt(),anyMap())).thenReturn(1);
        when(store.audit(anyString(),anyLong(),anyLong(),anyString(),anyString(),anyInt(),anyString(),anyString())).thenReturn(1);
    }
    @AfterEach void clear(){MetaContext.clear();}
    private Map<String,Object> row() {
        return new HashMap<>(Map.of("revision",5,"security_revision",2,"force_approval",false,"late_window_minutes",30,
            "reminder_at","09:00:00","automatic_reminder_limit",3,"reminder_dedupe_minutes",60));
    }
    @SuppressWarnings("unchecked") private Map<String,Object> saved(Map<String,Object> input) {
        return (Map<String,Object>)service.execute(input,"operation").get("policy");
    }
    @Test void missingPolicyUsesExplicitDefaultsAndAppendsAudit() {
        assertThat(saved(Map.of("expectedPolicyRevision",0))).containsEntry("revision",1).containsEntry("securityRevision",0)
            .containsEntry("reminderAt","09:00").containsEntry("automaticReminderLimit",3).containsEntry("reminderDedupeMinutes",60);
        verify(store).audit(anyString(),eq(1L),eq(7L),eq("membership"),eq("operation"),eq(0),anyString(),anyString());
    }
    @Test void experienceChangesAdvanceOnlyEditRevisionAndNormalizeDatabaseTimes() {
        when(store.lock(1L)).thenReturn(row());
        assertThat(saved(Map.of("expectedPolicyRevision",5,"reminderAt","10:30","quietStart","22:00","quietEnd","08:00")))
            .containsEntry("revision",6).containsEntry("securityRevision",2).containsEntry("quietEnd","08:00");
    }
    @Test void tighteningApprovalAdvancesSecurityGeneration() {
        when(store.lock(1L)).thenReturn(row());
        assertThat(saved(Map.of("expectedPolicyRevision",5,"forceApproval",true)))
            .containsEntry("revision",6).containsEntry("securityRevision",3);
    }
    @Test void staleRevisionCannotWriteOrAudit() {
        when(store.lock(1L)).thenReturn(row());
        assertThatThrownBy(()->saved(Map.of("expectedPolicyRevision",4))).isInstanceOf(com.auraboot.framework.exception.ConflictException.class);
        verify(store,never()).update(anyLong(),anyLong(),anyInt(),anyMap());
        verify(store,never()).audit(anyString(),anyLong(),anyLong(),anyString(),anyString(),anyInt(),anyString(),anyString());
    }
    @Test void concurrentInitialInsertDoesNotClaimSuccessOrAppendAudit() {
        when(store.insert(anyLong(),anyLong(),anyMap())).thenReturn(0);
        assertThatThrownBy(()->saved(Map.of("expectedPolicyRevision",0))).isInstanceOf(com.auraboot.framework.exception.ConflictException.class);
        verify(store,never()).audit(anyString(),anyLong(),anyLong(),anyString(),anyString(),anyInt(),anyString(),anyString());
    }
    @Test void explicitOperationReplayReturnsOriginalWireResultWithoutAnotherUpdate() {
        var payload=Map.<String,Object>of("expectedPolicyRevision",0);
        when(operations.claim(anyLong(),anyLong(),anyString(),anyString(),anyString())).thenReturn(0);
        when(operations.operation(1L,7L,AnnouncementOperatingPolicyService.UPDATE,"operation"))
            .thenReturn(Map.of("request_hash",codec.hash(payload),"result","{\"operationId\":\"operation\",\"policy\":{\"revision\":1}}"));
        assertThat(saved(payload)).containsEntry("revision",1);
        verifyNoInteractions(store);
    }
    @Test void replayCannotRevealPolicyAfterMembershipIsRecreated() {
        when(identities.findEnabledTenantMembership(1L,"actor")).thenReturn(new IdentityDirectoryAccessor.TenantUserIdentity(
            new IdentityDirectoryAccessor.UserIdentity(7L,"actor","actor","Actor"),"new-membership",71L));
        assertThatThrownBy(()->saved(Map.of("expectedPolicyRevision",0))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(operations,store);
    }
    @Test void unknownFieldsOrInvalidWindowsCannotMutatePolicy() {
        for(var input:List.of(Map.of("expectedPolicyRevision",0,"securityRevision",9),
                Map.of("expectedPolicyRevision",0,"quietStart","22:00"),
                Map.of("expectedPolicyRevision",0,"quietStart","22:00","quietEnd","22:00"),
                Map.of("expectedPolicyRevision",0,"reminderAt","24:00"),
                Map.of("expectedPolicyRevision",0,"automaticReminderLimit",4),
                Map.of("expectedPolicyRevision",0,"reminderDedupeMinutes",59))) {
            assertThatThrownBy(()->service.execute(new HashMap<>(input),UUID.randomUUID().toString()))
                .isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        }
        verify(store,never()).insert(anyLong(),anyLong(),anyMap());
    }
    @Test void administratorsCanExplicitlyClearTheDefaultFlowAndQuietWindow() {
        var existing=row();existing.put("default_definition_pid","old-flow");existing.put("quiet_start","22:00:00");existing.put("quiet_end","08:00:00");
        when(store.lock(1L)).thenReturn(existing);
        assertThat(saved(Map.of("expectedPolicyRevision",5,"defaultDefinitionPid","","quietStart","","quietEnd","")))
            .containsEntry("defaultDefinitionPid",null).containsEntry("quietStart",null).containsEntry("quietEnd",null)
            .containsEntry("securityRevision",2);
    }
    @Test void unavailableCurrentFlowUsesAHumanLabelWithoutPretendingItIsReady() {
        when(policies.current()).thenReturn(new AnnouncementTenantPolicyService.Policy(1,false,"old-flow",30));
        var choices=service.definitions();
        assertThat(choices).hasSize(1);
        assertThat(choices.getFirst().value()).isEqualTo("old-flow");
        assertThat(choices.getFirst().label().get("en-US")).contains("unavailable").doesNotContain("old-flow");
    }
    @Test void selectingAFlowRequiresCurrentQualifiedProviderReadiness() {
        assertThatThrownBy(()->saved(Map.of("expectedPolicyRevision",0,"defaultDefinitionPid","unavailable")))
            .isInstanceOf(com.auraboot.framework.exception.BusinessException.class);
        verify(store,never()).insert(anyLong(),anyLong(),anyMap());
    }
}
