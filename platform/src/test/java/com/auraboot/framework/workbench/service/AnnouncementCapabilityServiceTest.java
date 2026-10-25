package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.extension.WorkflowCapability;
import com.auraboot.framework.plugin.extension.approval.ApprovalContract;
import com.auraboot.framework.plugin.pf4j.WorkflowCapabilityRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnnouncementCapabilityServiceTest {
    final AnnouncementAccessService access=mock(AnnouncementAccessService.class);
    final UserPermissionService permissions=mock(UserPermissionService.class);
    final AnnouncementTenantPolicyService policies=mock(AnnouncementTenantPolicyService.class);
    final AnnouncementApprovalCatalog catalog=mock(AnnouncementApprovalCatalog.class);
    final WorkflowCapabilityRegistry workflows=mock(WorkflowCapabilityRegistry.class);
    final MultipartProperties uploads=new MultipartProperties();
    final AnnouncementCapabilityService service=new AnnouncementCapabilityService(access,permissions,policies,catalog,workflows,uploads,new ObjectMapper());
    @BeforeEach void setup() {
        MetaContext.setContext(1L,7L,"author","Author");
        when(access.actor("publish")).thenReturn(7L);
        when(policies.current()).thenReturn(new AnnouncementTenantPolicyService.Policy(3,false,"flow",30));
    }
    @AfterEach void clear(){MetaContext.clear();}
    @Test void currentUploadLimitsAndUnavailableChannelsAreExplicitWithoutAProviderCall() {
        uploads.setMaxFileSize(DataSize.ofMegabytes(2));uploads.setMaxRequestSize(DataSize.ofMegabytes(4));
        var view=service.capabilities();
        assertThat((Map<String,Object>)view.get("attachments")).containsEntry("maxFileBytes",2097152L).containsEntry("maxRequestBytes",4194304L).containsEntry("maxCount",10);
        assertThat(view.get("channels")).asList().contains(Map.of("code","wechat_mini","ready",false,"reasonCode","CHANNEL_NOT_CONFIGURED"));
        assertThat(view.get("allTenantAllowed")).isEqualTo(false);verifyNoInteractions(workflows);
    }
    @Test void forcedApprovalCannotAdvertiseAnExemptionDespiteItsGrant() {
        ReflectionTestUtils.setField(service,"deliveryEnabled",true);
        when(permissions.hasPermission(7L,"announcement.announcement.exempt")).thenReturn(true);
        when(policies.current()).thenReturn(new AnnouncementTenantPolicyService.Policy(4,true,null,30));
        assertThat(service.capabilities().get("modes")).asList().contains(Map.of("code","exempt","ready",false,"reasonCode","APPROVAL_REQUIRED"));
    }
    @Test void absentProviderDoesNotFallBackToAnApproval() {
        assertThat(service.approvalReadiness()).containsExactlyInAnyOrderEntriesOf(Map.of("ready",false,"reasonCode","APPROVAL_PROVIDER_UNAVAILABLE"));
        verify(access).actor("submit");verifyNoInteractions(workflows);
    }
    @Test void missingDefaultDefinitionDoesNotQueryAnArbitraryWorkflow() {
        when(catalog.available()).thenReturn(true);
        when(policies.current()).thenReturn(new AnnouncementTenantPolicyService.Policy(4,false,null,30));
        assertThat(service.approvalReadiness()).containsEntry("ready",false).containsEntry("reasonCode","APPROVAL_DEFAULT_DEFINITION_REQUIRED");
        verifyNoInteractions(workflows);
    }
    @Test void installingBpmWithoutATenantDefaultDoesNotAdvertiseApprovalAsReady() {
        ReflectionTestUtils.setField(service,"deliveryEnabled",true);
        when(permissions.hasPermission(7L,"announcement.announcement.submit")).thenReturn(true);
        when(catalog.available()).thenReturn(true);
        when(policies.current()).thenReturn(new AnnouncementTenantPolicyService.Policy(4,false,null,30));
        assertThat(service.capabilities().get("modes")).asList().contains(Map.of("code","approval","ready",false,
            "reasonCode","APPROVAL_DEFAULT_DEFINITION_REQUIRED"));
        assertThat(service.modeOptions()).allSatisfy(option->assertThat(option.get("disabled")).isEqualTo(true));
        assertThat(service.defaultDefinitionOptions()).containsExactly(new AnnouncementAudienceService.Option("tenant_default",
            Map.of("zh-CN","请管理员配置默认流程","en-US","Administrator must configure a default workflow")));
        verifyNoInteractions(workflows);verify(catalog,never()).choices();
    }
    @Test void modeOptionsKeepPermissionFailuresVisibleWithoutCallingTheProvider() {
        ReflectionTestUtils.setField(service,"deliveryEnabled",true);
        var options=service.modeOptions();
        assertThat(options).hasSize(2).allSatisfy(option->{
            assertThat(option.get("disabled")).isEqualTo(true);
            assertThat((Map<String,String>)option.get("label")).containsKey("zh-CN").containsKey("en-US");
        });
        assertThat(service.defaultDefinitionOptions().getFirst().value()).isEqualTo("tenant_default");
        verifyNoInteractions(workflows);verify(catalog,never()).choices();
    }
    @Test void publisherDefaultOptionsNeverExposeCatalogChoicesOrTheProviderProof() {
        when(permissions.hasPermission(7L,"announcement.announcement.submit")).thenReturn(true);
        when(catalog.available()).thenReturn(true);
        when(workflows.execute(eq(ApprovalContract.READINESS),any())).thenReturn(new WorkflowCapability.WorkflowResult(Map.of(
            "ready",true,"definition",Map.of("pid","flow","version",2,"hash","a".repeat(64)),"definitionName","Tenant approval","secret","never public")));
        assertThat(service.defaultDefinitionOptions()).containsExactly(new AnnouncementAudienceService.Option("tenant_default",
            Map.of("zh-CN","租户默认流程：Tenant approval","en-US","Tenant default: Tenant approval")));
        verify(catalog,never()).choices();
    }
    @Test void aReadyNeutralPayloadReturnsOnlyTheBoundDefaultDefinition() {
        when(catalog.available()).thenReturn(true);
        when(workflows.execute(eq(ApprovalContract.READINESS),any())).thenReturn(new WorkflowCapability.WorkflowResult(Map.of(
            "ready",true,"definition",Map.of("pid","flow","version",2,"hash","a".repeat(64)),"definitionName","Tenant approval","secret","never public")));
        assertThat(service.approvalReadiness()).containsExactlyInAnyOrderEntriesOf(Map.of("ready",true,"reasonCode","READY","definitionPid","flow","definitionVersion",2,"definitionName","Tenant approval"));
    }
    @Test void aProviderCannotSubstituteAnotherDefinition() {
        when(catalog.available()).thenReturn(true);
        when(workflows.execute(eq(ApprovalContract.READINESS),any())).thenReturn(new WorkflowCapability.WorkflowResult(Map.of(
            "ready",true,"definition",Map.of("pid","other","version",2,"hash","a".repeat(64)),"definitionName","Other definition")));
        assertThatThrownBy(service::approvalReadiness).isInstanceOf(IllegalStateException.class);
    }
}
