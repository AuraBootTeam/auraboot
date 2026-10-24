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
}
