package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.openplatform.entity.*;
import com.auraboot.framework.openplatform.mapper.*;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenPlatformAuditAuthorizationTest {
    @Mock ApplicationInstallationMapper installationMapper;
    @Mock ExternalApplicationMapper applicationMapper;
    @Mock ExternalApplicationMemberMapper memberMapper;
    @Mock OpenApiCallAuditMapper auditMapper;
    @Mock UserPermissionService userPermissionService;
    @InjectMocks OpenPlatformManagementService service;
    @BeforeEach void context() {MetaContext.setContext(42L,7L,"reader-pid","reader");}
    @AfterEach void clear() {MetaContext.clear();}
    private void role(String role) {
        var installation=new ApplicationInstallation();installation.setApplicationId(9L);installation.setTenantId(42L);
        when(installationMapper.findByTenantAndPid(42L,"installation-a")).thenReturn(installation);
        var app=new ExternalApplication();app.setId(9L);app.setOwnerTenantId(42L);
        when(applicationMapper.selectById(9L)).thenReturn(app);
        var member=new ExternalApplicationMember();member.setRole(role);
        when(memberMapper.findMember(42L,9L,"reader-pid")).thenReturn(member);
    }
    @Test void viewerCannotReadCapturedContentEvenWhenTheyKnowTheAuditPid() {
        role("viewer");assertThatThrownBy(()->service.getCallAuditDetail("installation-a","audit-one")).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(auditMapper);
    }
    @ParameterizedTest @ValueSource(strings={"owner","maintainer"})
    void payloadReadIsScopedToTenantInstallationAndAudit(String role) throws Exception {
        role(role);var audit=new OpenApiCallAudit();audit.setPid("audit-one");audit.setRequestId("request-one");
        audit.setHttpDetails(new ObjectMapper().readTree("{\"request\":{\"state\":\"empty\"}}"));
        when(auditMapper.findDetail(42L,"installation-a","audit-one")).thenReturn(audit);
        var detail=service.getCallAuditDetail("installation-a","audit-one");
        assertThat(detail.auditPid()).isEqualTo("audit-one");assertThat(detail.captureState()).isEqualTo("captured");
        verify(auditMapper).findDetail(42L,"installation-a","audit-one");
    }
    @Test void historicalSummaryDoesNotInventHttpContent() {
        role("owner");var audit=new OpenApiCallAudit();audit.setPid("historical");
        when(auditMapper.findDetail(42L,"installation-a","historical")).thenReturn(audit);
        var detail=service.getCallAuditDetail("installation-a","historical");
        assertThat(detail.captureState()).isEqualTo("not_captured");assertThat(detail.httpDetails()).isNull();
    }
    @Test void anotherTenantsInstallationFailsBeforeReadingAnyAudit() {
        assertThatThrownBy(()->service.getCallAuditDetail("foreign-installation","audit-one")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(installationMapper).findByTenantAndPid(42L,"foreign-installation");verifyNoInteractions(auditMapper);
    }
    @Test void anAuditFromAnotherInstallationDoesNotResolve() {
        role("maintainer");assertThatThrownBy(()->service.getCallAuditDetail("installation-a","other-installation-audit"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(auditMapper).findDetail(42L,"installation-a","other-installation-audit");
    }
}
