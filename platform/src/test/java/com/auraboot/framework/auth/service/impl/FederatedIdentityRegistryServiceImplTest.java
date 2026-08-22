package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.dto.ExternalIdentityAttributes;
import com.auraboot.framework.auth.dto.ExternalIdentityLinkSummary;
import com.auraboot.framework.auth.dto.FederatedLoginContext;
import com.auraboot.framework.auth.entity.ExternalIdentityLink;
import com.auraboot.framework.auth.mapper.ExternalIdentityLinkMapper;
import com.auraboot.framework.auth.mapper.IdentityProviderInstanceMapper;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.saas.config.service.SystemModeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FederatedIdentityRegistryServiceImplTest {

    @Mock
    private IdentityProviderInstanceMapper identityProviderInstanceMapper;
    @Mock
    private ExternalIdentityLinkMapper externalIdentityLinkMapper;
    @Mock
    private SystemModeService systemModeService;

    private FederatedIdentityRegistryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FederatedIdentityRegistryServiceImpl(
                identityProviderInstanceMapper,
                externalIdentityLinkMapper,
                systemModeService);
    }

    @Test
    void singleModeIgnoresClientTenantAndUsesDefaultBusinessTenant() {
        FederatedLoginContext resolved = context(2L, 31L);
        when(systemModeService.isSingleTenant()).thenReturn(true);
        when(systemModeService.getDefaultTenantId()).thenReturn(2L);
        when(identityProviderInstanceMapper.resolveFederatedContext(
                "business-web", "default-business-web", "wechat", 2L))
                .thenReturn(resolved);

        assertThat(service.resolveLoginContext(null, null, "wechat", 999L)).isSameAs(resolved);
        verify(identityProviderInstanceMapper).resolveFederatedContext(
                "business-web", "default-business-web", "wechat", 2L);
    }

    @Test
    void multiModeRequiresAnExplicitTenantForFederatedRouting() {
        when(systemModeService.isSingleTenant()).thenReturn(false);

        assertThatThrownBy(() -> service.resolveLoginContext(
                "business-web", "default-business-web", "oidc", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tenant is required");
    }

    @Test
    void linkedIdentityProjectionIsExplicitlyScopedToUserAndTenant() {
        ExternalIdentityLinkSummary summary = new ExternalIdentityLinkSummary();
        summary.setProvider("company-oidc");
        when(externalIdentityLinkMapper.listActiveByUser(7L, 2L))
                .thenReturn(List.of(summary));

        assertThat(service.listActiveLinks(7L, 2L))
                .extracting(ExternalIdentityLinkSummary::getProvider)
                .containsExactly("company-oidc");
        verify(externalIdentityLinkMapper).listActiveByUser(7L, 2L);
    }

    @Test
    void disabledOrUnboundProviderFailsClosed() {
        when(systemModeService.isSingleTenant()).thenReturn(false);
        when(identityProviderInstanceMapper.resolveFederatedContext(
                "business-web", "supplier-portal", "supplier-sso", 2L))
                .thenReturn(null);

        assertThatThrownBy(() -> service.resolveLoginContext(
                "business-web", "supplier-portal", "supplier-sso", 2L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No active identity provider");
    }

    @Test
    void newSubjectCreatesAnInstanceScopedIdentityLink() {
        FederatedLoginContext context = context(2L, 31L);
        ExternalIdentityAttributes attributes = new ExternalIdentityAttributes(
                "openid-7", "Alice", null, "{\"unionid\":\"union-7\"}");
        when(externalIdentityLinkMapper.findActiveBySubject(31L, "openid-7")).thenReturn(null);
        when(externalIdentityLinkMapper.findActiveByUserAndInstance(7L, 31L)).thenReturn(null);

        ExternalIdentityLink created = service.link(context, 7L, attributes);

        ArgumentCaptor<ExternalIdentityLink> captor = ArgumentCaptor.forClass(ExternalIdentityLink.class);
        verify(externalIdentityLinkMapper).insert(captor.capture());
        assertThat(created).isSameAs(captor.getValue());
        assertThat(created.getPid()).hasSize(26);
        assertThat(created.getTenantId()).isEqualTo(2L);
        assertThat(created.getApplicationId()).isEqualTo(11L);
        assertThat(created.getIdentityProviderInstanceId()).isEqualTo(31L);
        assertThat(created.getUserId()).isEqualTo(7L);
        assertThat(created.getExternalSubject()).isEqualTo("openid-7");
        assertThat(created.getClaims()).contains("union-7");
    }

    @Test
    void subjectAlreadyLinkedToAnotherUserIsRejected() {
        ExternalIdentityLink existing = new ExternalIdentityLink();
        existing.setUserId(8L);
        when(externalIdentityLinkMapper.findActiveBySubject(31L, "subject-1"))
                .thenReturn(existing);

        assertThatThrownBy(() -> service.link(
                context(2L, 31L),
                7L,
                new ExternalIdentityAttributes("subject-1", null, null, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("another user");
    }

    @Test
    void userCannotBindTwoSubjectsInOneIdentityProviderInstance() {
        ExternalIdentityLink existing = new ExternalIdentityLink();
        existing.setUserId(7L);
        existing.setExternalSubject("subject-old");
        when(externalIdentityLinkMapper.findActiveBySubject(31L, "subject-new"))
                .thenReturn(null);
        when(externalIdentityLinkMapper.findActiveByUserAndInstance(7L, 31L))
                .thenReturn(existing);

        assertThatThrownBy(() -> service.link(
                context(2L, 31L),
                7L,
                new ExternalIdentityAttributes("subject-new", null, null, "{}")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("different identity");
    }

    private FederatedLoginContext context(Long tenantId, Long instanceId) {
        FederatedLoginContext context = new FederatedLoginContext();
        context.setApplicationId(11L);
        context.setLoginChannelId(21L);
        context.setIdentityProviderInstanceId(instanceId);
        context.setTenantId(tenantId);
        context.setIdentityProviderCode("wechat");
        context.setProviderType("wechat_web");
        context.setProviderConfig("{}");
        context.setChannelSettings("{}");
        return context;
    }
}
