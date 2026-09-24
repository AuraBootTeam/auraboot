package com.auraboot.framework.user.service.impl;

import com.auraboot.framework.user.dao.entity.UserApplicationPreference;
import com.auraboot.framework.user.mapper.UserApplicationPreferenceMapper;
import com.auraboot.framework.user.service.UserApplicationPreferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserApplicationPreferenceServiceImplTest {
    @Mock private UserApplicationPreferenceMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void lastTenantIsApplicationScopedAndStoresOnlyPublicPid() {
        var service = new UserApplicationPreferenceServiceImpl(mapper, objectMapper);

        service.setLastTenant(10L, 20L, "01TENANTPUBLICPID000000000");

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(mapper).upsert(
                org.mockito.ArgumentMatchers.anyString(), eq(10L), eq(20L),
                eq(UserApplicationPreferenceService.LAST_TENANT_KEY), json.capture(), eq(1));
        assertThat(json.getValue()).contains("\"tenantPid\":\"01TENANTPUBLICPID000000000\"");
        assertThat(json.getValue()).doesNotContain("tenantId");
    }

    @Test
    void readsLastTenantFromRequestedApplicationOnly() {
        var service = new UserApplicationPreferenceServiceImpl(mapper, objectMapper);
        UserApplicationPreference preference = new UserApplicationPreference();
        preference.setPreferenceValue(objectMapper.createObjectNode().put("tenantPid", "tenant-a"));
        when(mapper.find(10L, 21L, UserApplicationPreferenceService.LAST_TENANT_KEY))
                .thenReturn(preference);

        assertThat(service.getLastTenantPid(10L, 21L)).isEqualTo("tenant-a");
        verify(mapper).find(10L, 21L, UserApplicationPreferenceService.LAST_TENANT_KEY);
    }

    @Test
    void rejectsUnknownKeysUntilTheyHaveARegisteredSchema() {
        var service = new UserApplicationPreferenceServiceImpl(mapper, objectMapper);

        assertThatThrownBy(() -> service.set(
                10L, 20L, "random.unreviewed", objectMapper.createObjectNode(), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unregistered");
    }

    @Test
    void mapperUsesQueryForReadAndDmlForUpsert() throws Exception {
        assertThat(UserApplicationPreferenceMapper.class
                .getMethod("find", Long.class, Long.class, String.class)
                .getAnnotation(Select.class)).isNotNull();
        assertThat(UserApplicationPreferenceMapper.class
                .getMethod("upsert", String.class, Long.class, Long.class, String.class, String.class, int.class)
                .getAnnotation(Insert.class)).isNotNull();
    }
}
