package com.auraboot.framework.workbench.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.file.entity.FileEntity;
import com.auraboot.framework.file.service.FileService;
import com.auraboot.framework.workbench.mapper.AnnouncementLifecycleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;

class AnnouncementPolicyServiceTest {
    private final FileService files=mock(FileService.class);
    private final AnnouncementLifecycleMapper records=mock(AnnouncementLifecycleMapper.class);
    private final AnnouncementPolicyService service=new AnnouncementPolicyService(new ObjectMapper().findAndRegisterModules(),files,records);
    @BeforeEach void context(){MetaContext.setContext(1L,7L,"u7","Author");}
    @AfterEach void clear(){MetaContext.clear();}
    @Test void importantDefaultIsIndependentFromAnExplicitOptOut() {
        var policy=service.parse(Map.of());
        assertThat(policy.acknowledgementRequired("important")).isTrue();
        assertThat(policy.acknowledgementRequired("normal")).isFalse();
        assertThat(service.parse(Map.of("acknowledgementRequired",false)).acknowledgementRequired("important")).isFalse();
        assertThat(policy.acknowledgementDays()).isEqualTo(7);
    }
    @Test void protectedPolicyFieldsAndCoercedValuesAreRejected() {
        for(Map<String,Object> input:List.of(Map.<String,Object>of("tenantId",2),Map.<String,Object>of("acknowledgementRequired","false"),
            Map.<String,Object>of("acknowledgementDays",1.5),Map.<String,Object>of("scheduledAt","tomorrow"))) {
            assertThatThrownBy(()->service.parse(input)).isInstanceOf(BusinessException.class);
        }
    }
    @Test void newWorkflowOverridesAreRejectedWhileFrozenLegacyPoliciesRemainReadable() {
        var legacy=Map.<String,Object>of("approvalMode","approval","definitionPid","legacy-flow");
        assertThat(service.parseStored(legacy).definitionPid()).isEqualTo("legacy-flow");
        assertThatThrownBy(()->service.validateDraft(legacy,null)).isInstanceOf(BusinessException.class)
            .hasMessageContaining("tenant_default_required");
        assertThatThrownBy(()->AnnouncementPolicyService.requireTenantDefault(service.parseStored(legacy)))
            .isInstanceOf(BusinessException.class);
        verifyNoInteractions(files);
    }
    @Test void legacyDraftRequiresAnExplicitSwitchToTheTenantDefault() {
        when(records.draftSettings(1L,"notice")).thenReturn("{\"approvalMode\":\"approval\",\"definitionPid\":\"legacy-flow\"}");
        assertThatThrownBy(()->service.fromForm(Map.of("ack_days",14),"notice")).isInstanceOf(BusinessException.class);
        var encoded=service.fromForm(Map.of("approval_definition","tenant_default","ack_days",14),"notice");
        assertThat(encoded).contains("\"definitionPid\":null").contains("\"approvalMode\":\"approval\"");
        assertThatThrownBy(()->service.fromForm(Map.of("approval_definition","another-flow"),"notice"))
            .isInstanceOf(BusinessException.class);
    }
    @Test void foreignTenantAttachmentIsRejectedBeforeItsMetadataIsRead() {
        var policy=service.parse(Map.of("attachmentPids",List.of("foreign-file")));
        assertThatThrownBy(()->service.verifyAttachments(policy,"notice",false)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(files);
    }
    @Test void finalizedButUnownedAndUnlinkedFileIsRejected() {
        when(records.finalizedFileInTenant(1L,"file")).thenReturn(true);
        FileEntity file=new FileEntity();file.setPid("file");file.setStatus("success");file.setDeletedFlag(false);file.setCreatedBy(8L);
        when(files.findByPid("file")).thenReturn(file);
        when(files.getFilesByEntityAndField("ab_announcement","notice","attachments")).thenReturn(List.of());
        var policy=service.parse(Map.of("attachmentPids",List.of("file")));
        assertThatThrownBy(()->service.verifyAttachments(policy,"notice",true)).isInstanceOf(AccessDeniedException.class);
        verify(files,never()).lockRetention("file");
    }
    @Test void ownedFinalFileIsRetainedOnlyForSubmission() {
        when(records.finalizedFileInTenant(1L,"file")).thenReturn(true);
        FileEntity file=new FileEntity();file.setPid("file");file.setStatus("success");file.setDeletedFlag(false);file.setCreatedBy(7L);
        when(files.findByPid("file")).thenReturn(file);when(files.lockRetention("file")).thenReturn(true);
        var policy=service.parse(Map.of("attachmentPids",List.of("file")));
        service.verifyAttachments(policy,null,false);verify(files,never()).lockRetention("file");
        service.verifyAttachments(policy,null,true);verify(files).lockRetention("file");
    }
    @Test void normalizedCommandTimeRemainsIsoAcrossStoredPolicyRoundTrip() throws Exception {
        var instant=java.time.Instant.parse("2030-10-26T04:00:00Z");
        when(records.draftSettings(1L,"notice")).thenReturn("{}");
        var encoded=service.fromForm(Map.of("scheduled_at",instant,"ack_required",true),"notice");
        var persisted=new ObjectMapper().readValue(encoded,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        assertThat(persisted.get("scheduledAt")).isEqualTo(instant.toString());
        assertThat(service.parseStored(persisted).scheduledAt()).isEqualTo(instant);
        when(records.draftSettings(1L,"notice")).thenReturn(encoded);
        assertThat(service.fromForm(Map.of("ack_days",14),"notice")).contains(instant.toString());
    }
    @Test void unchangedNativeFileJsonIsNormalizedWithoutTrustingPresentation() {
        when(records.draftSettings(1L,"notice")).thenReturn("{}");
        assertThat(service.fromForm(Map.of("attachments","[]"),"notice")).contains("\"attachmentPids\":[]");
        assertThatThrownBy(()->service.fromForm(Map.of("attachments","{\"fileId\":\"foreign\"}"),"notice")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.fromForm(Map.of("attachments","broken"),"notice")).isInstanceOf(BusinessException.class);
    }
}
