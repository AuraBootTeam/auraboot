package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.dto.IdentityProviderSaveRequest;
import com.auraboot.framework.auth.dto.IdentityProviderSummary;
import com.auraboot.framework.auth.entity.IdentityProviderInstance;
import com.auraboot.framework.auth.mapper.IdentityProviderInstanceMapper;
import com.auraboot.framework.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentityProviderManagementServiceImplTest {

    @Mock
    private IdentityProviderInstanceMapper mapper;

    private IdentityProviderManagementServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new IdentityProviderManagementServiceImpl(mapper, new ObjectMapper());
    }

    @Test
    void rejectsInlineSecretsAtAnyConfigDepth() {
        IdentityProviderSaveRequest request = validRequest();
        request.setConfig("""
                {
                  "issuerUrl":"https://issuer.example.test",
                  "redirectUris":["https://app.example.test/oauth/callback"],
                  "credentials":{"clientSecret":"must-not-be-here"}
                }
                """);

        assertThatThrownBy(() -> service.save(request, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Secrets must be stored in CloudConfig");

        verify(mapper, never()).insert(any(IdentityProviderInstance.class));
    }

    @Test
    void rejectsRemotePlainHttpRedirectButAllowsLocalDevelopmentHttp() {
        IdentityProviderSaveRequest remote = validRequest();
        remote.setConfig("{\"redirectUris\":[\"http://app.example.test/oauth/callback\"]}");

        assertThatThrownBy(() -> service.save(remote, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Redirect URI is invalid");

        IdentityProviderSaveRequest local = validRequest();
        local.setConfig("{\"redirectUris\":[\"http://localhost:5173/oauth/callback\"]}");
        when(mapper.findApplicationId("business-web")).thenReturn(10L);
        when(mapper.findChannelId(10L, "default-business-web", 100L)).thenReturn(20L);
        when(mapper.findBindingId(20L, 30L)).thenReturn(null);
        AtomicReference<IdentityProviderInstance> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            IdentityProviderInstance instance = invocation.getArgument(0);
            instance.setId(30L);
            inserted.set(instance);
            return 1;
        }).when(mapper).insert(any(IdentityProviderInstance.class));
        when(mapper.listManaged("business-web", 100L)).thenAnswer(invocation ->
                List.of(summary(inserted.get().getPid(), "company-oidc", "business-web")));

        assertThat(service.save(local, 100L).getCode()).isEqualTo("company-oidc");
    }

    @Test
    void savesMobileProviderIntoTheExplicitMobileApplicationAndChannel() {
        IdentityProviderSaveRequest request = validRequest();
        request.setApplicationCode("business-mobile");
        request.setChannelCode("default-business-mobile");
        request.setConfig("{\"redirectUris\":[\"auraboot://oauth/callback\"]}");
        request.setSecretRef("cloud-config:company_mobile_oidc");

        when(mapper.findApplicationId("business-mobile")).thenReturn(11L);
        when(mapper.findChannelId(11L, "default-business-mobile", 100L)).thenReturn(21L);
        when(mapper.findBindingId(21L, 31L)).thenReturn(null);
        AtomicReference<IdentityProviderInstance> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            IdentityProviderInstance instance = invocation.getArgument(0);
            instance.setId(31L);
            inserted.set(instance);
            return 1;
        }).when(mapper).insert(any(IdentityProviderInstance.class));
        when(mapper.listManaged("business-mobile", 100L)).thenAnswer(invocation ->
                List.of(summary(inserted.get().getPid(), "company-oidc", "business-mobile")));

        assertThat(service.save(request, 100L).getApplicationCode())
                .isEqualTo("business-mobile");

        ArgumentCaptor<IdentityProviderInstance> instance =
                ArgumentCaptor.forClass(IdentityProviderInstance.class);
        verify(mapper).insert(instance.capture());
        assertThat(instance.getValue().getApplicationId()).isEqualTo(11L);
        assertThat(instance.getValue().getTenantId()).isEqualTo(100L);
        assertThat(instance.getValue().getSecretRef())
                .isEqualTo("cloud-config:company_mobile_oidc");
        verify(mapper).insertBinding(
                any(String.class),
                org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.eq(21L),
                org.mockito.ArgumentMatchers.eq(31L),
                org.mockito.ArgumentMatchers.eq("oidc"),
                org.mockito.ArgumentMatchers.eq("active"),
                org.mockito.ArgumentMatchers.eq(100));
    }

    @Test
    void statusUpdateFindsTenantOwnedMobileInstanceWithoutWebAssumption() {
        IdentityProviderInstance instance = new IdentityProviderInstance();
        instance.setId(31L);
        instance.setPid("mobile-idp-pid");
        instance.setStatus("active");
        when(mapper.findEditableByPid("mobile-idp-pid", 100L)).thenReturn(instance);

        service.setStatus("mobile-idp-pid", "disabled", 100L);

        assertThat(instance.getStatus()).isEqualTo("disabled");
        verify(mapper).updateById(instance);
        verify(mapper).updateBindingStatus(31L, "disabled");
    }

    private IdentityProviderSaveRequest validRequest() {
        IdentityProviderSaveRequest request = new IdentityProviderSaveRequest();
        request.setCode("company-oidc");
        request.setDisplayName("Company OIDC");
        request.setProviderType("oidc");
        request.setConfig("{\"redirectUris\":[\"https://app.example.test/oauth/callback\"]}");
        return request;
    }

    private IdentityProviderSummary summary(String pid, String code, String applicationCode) {
        IdentityProviderSummary summary = new IdentityProviderSummary();
        summary.setPid(pid);
        summary.setCode(code);
        summary.setApplicationCode(applicationCode);
        return summary;
    }
}
