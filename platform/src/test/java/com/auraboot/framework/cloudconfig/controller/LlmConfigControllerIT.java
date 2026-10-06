package com.auraboot.framework.cloudconfig.controller;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.cloudconfig.dto.CloudConfigResponse;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.TenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real permission interceptor and PostgreSQL persistence for the LLM-only boundary. */
class LlmConfigControllerIT extends BaseIntegrationTest {
    private static final String API = "/api/llm-config";
    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private CloudConfigService configs;
    @Autowired private TenantService tenants;
    @Autowired private PermissionMapper permissions;
    @Autowired private RolePermissionMapper grants;
    @Autowired private UserPermissionService userPermissions;
    @Autowired private AdminRoleChecker adminRoles;
    @Autowired private ObjectMapper json;
    private RolePermission aiGrant;

    @BeforeEach
    void grantOnlyModelService() {
        applyTestMetaContext();
        adminRoles.invalidateAll();
        assertThat(adminRoles.hasRole(getTestTenant().getId(), getTestUser().getId(), RoleCodes.PLATFORM_ADMIN)).isFalse();
        assertThat(adminRoles.hasRole(getTestTenant().getId(), getTestUser().getId(), RoleCodes.TENANT_ADMIN)).isFalse();
        Permission permission = permissions.findByCode("ai_center");
        if (permission == null) {
            permission = new Permission();
            permission.setPid(UniqueIdGenerator.generate());
            permission.setTenantId(getTestTenant().getId());
            permission.setCode("ai_center");
            permission.setName("Model service");
            permission.setResourceType("system");
            permission.setResourceCode("ai_center");
            permission.setAction("manage");
            permission.setSource("integration_test");
            permission.setStatus("active");
            permission.setDeletedFlag(false);
            permission.setCreatedAt(Instant.now());
            permission.setUpdatedAt(Instant.now());
            permissions.insert(permission);
        }
        aiGrant = new RolePermission();
        aiGrant.setPid(UniqueIdGenerator.generate());
        aiGrant.setTenantId(getTestTenant().getId());
        aiGrant.setRoleId(getTestRole().getId());
        aiGrant.setPermissionId(permission.getId());
        aiGrant.setGrantType("grant");
        aiGrant.setStatus("active");
        aiGrant.setDeletedFlag(false);
        aiGrant.setCreatedAt(Instant.now());
        aiGrant.setUpdatedAt(Instant.now());
        grants.insert(aiGrant);
        evict();
        assertThat(userPermissions.getUserPermissionCodes(getTestUser().getId()))
                .contains("ai_center").doesNotContain("sys.cloud_config.update", "system_management");
    }

    @Test
    void modelOnlyMemberCanCreateReadUpdateTestAndDeleteOwnLlmConfig() throws Exception {
        String provider = "it_" + UniqueIdGenerator.generate().substring(0, 16);
        mvc().perform(post(API).contentType(MediaType.APPLICATION_JSON).content(payload("llm", "tenant", provider, null, 1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0"));
        CloudConfigResponse created = find(provider);
        assertThat(created.getTenantId()).isEqualTo(getTestTenant().getId());
        assertThat(created.getConfig()).doesNotContain("test-secret");
        mvc().perform(get(API)).andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.pid == '" + created.getPid() + "')]").isNotEmpty());
        var update = request("llm", provider);
        update.setConfig(created.getConfig());
        update.setPriority(9);
        mvc().perform(put(API + "/" + created.getPid()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(update)))
                .andExpect(status().isOk());
        assertThat(find(provider).getPriority()).isEqualTo(9);
        assertThat(json.readTree(configs.getByPidDecrypted(created.getPid()).getConfig()).get("apiKey").asText()).isEqualTo("test-secret");
        update.setConfig("{\"apiKey\":\"rotated-test-secret\"}");
        mvc().perform(put(API + "/" + created.getPid()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(update))).andExpect(status().isOk());
        applyTestMetaContext();
        assertThat(json.readTree(configs.getByPidDecrypted(created.getPid()).getConfig()).get("apiKey").asText()).isEqualTo("rotated-test-secret");
        assertThat(configs.listConfigs("tenant").stream().filter(c -> provider.equals(c.getProviderCode())).count()).isEqualTo(1);
        mvc().perform(post(API + "/" + created.getPid() + "/test")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ok"));
        mvc().perform(delete(API + "/" + created.getPid())).andExpect(status().isOk());
        applyTestMetaContext();
        assertThat(configs.getConfigMasked(created.getPid())).isNull();
        mvc().perform(get("/api/admin/cloud-config")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("409"));
        mvc().perform(get("/api/admin/account-security-policy")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("409"));
        mvc().perform(get("/api/admin/system-preferences")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("409"));
    }

    @Test
    void nonLlmPayloadAndExistingCloudPidAreRejectedWithoutMutation() throws Exception {
        String provider = "sms_" + UniqueIdGenerator.generate().substring(0, 16);
        CloudConfigSaveRequest fixture = request("sms", provider);
        configs.saveConfig(fixture);
        CloudConfigResponse existing = find(provider);
        mvc().perform(post(API).contentType(MediaType.APPLICATION_JSON).content(payload("sms", "tenant", provider + "_bad", null, 8)))
                .andExpect(status().isForbidden());
        mvc().perform(post(API).contentType(MediaType.APPLICATION_JSON).content(payload("llm", "tenant", provider, existing.getPid(), 8)))
                .andExpect(status().isForbidden());
        mvc().perform(get(API + "/" + existing.getPid())).andExpect(status().isForbidden());
        mvc().perform(delete(API + "/" + existing.getPid())).andExpect(status().isForbidden());
        mvc().perform(post(API + "/" + existing.getPid() + "/test")).andExpect(status().isForbidden());
        applyTestMetaContext();
        assertThat(configs.getConfigMasked(existing.getPid()).getServiceType()).isEqualTo("sms");
        assertThat(configs.getConfigMasked(existing.getPid()).getPriority()).isEqualTo(1);
        assertThat(configs.listConfigs("tenant")).noneMatch(c -> (provider + "_bad").equals(c.getProviderCode()));
        mvc().perform(get(API)).andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.pid == '" + existing.getPid() + "')]").isEmpty());
    }

    @Test
    void foreignTenantLlmPidIsRejectedForEveryOperation() throws Exception {
        Tenant foreign = new Tenant();
        foreign.setName("LLM boundary " + UniqueIdGenerator.generate());
        foreign.setStatus("active");
        foreign = tenants.createTenant(foreign);
        String provider = "foreign_" + UniqueIdGenerator.generate().substring(0, 12);
        MetaContext.setContext(foreign.getId(), getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());
        configs.saveConfig(request("llm", provider));
        CloudConfigResponse fixture = find(provider);
        applyTestMetaContext();
        mvc().perform(get(API + "/" + fixture.getPid())).andExpect(status().isForbidden());
        mvc().perform(post(API).contentType(MediaType.APPLICATION_JSON).content(payload("llm", "tenant", provider, fixture.getPid(), 8)))
                .andExpect(status().isForbidden());
        mvc().perform(delete(API + "/" + fixture.getPid())).andExpect(status().isForbidden());
        mvc().perform(post(API + "/" + fixture.getPid() + "/test")).andExpect(status().isForbidden());
        applyTestMetaContext();
        assertThat(configs.getConfigMasked(fixture.getPid()).getPriority()).isEqualTo(1);
        assertThat(configs.getConfigMasked(fixture.getPid()).getTenantId()).isEqualTo(foreign.getId());
        mvc().perform(get(API)).andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.pid == '" + fixture.getPid() + "')]").isEmpty());
    }

    @Test
    void platformLevelRequiresPlatformAdminAndInvalidLevelIsRejected() throws Exception {
        mvc().perform(get(API).param("level", "platform")).andExpect(status().isForbidden());
        mvc().perform(get(API).param("level", "invalid")).andExpect(status().isBadRequest());
        String provider = "global_" + UniqueIdGenerator.generate().substring(0, 12);
        mvc().perform(post(API).contentType(MediaType.APPLICATION_JSON).content(payload("llm", "platform", provider, null, 1)))
                .andExpect(status().isForbidden());
        applyTestMetaContext();
        assertThat(configs.listConfigs("platform")).noneMatch(c -> provider.equals(c.getProviderCode()));
    }

    @Test
    void revokingModelPermissionRejectsTheSameEndpoint() throws Exception {
        mvc().perform(get(API)).andExpect(status().isOk());
        applyTestMetaContext();
        grants.deleteById(aiGrant.getId());
        evict();
        assertThat(userPermissions.getUserPermissionCodes(getTestUser().getId())).doesNotContain("ai_center");
        mvc().perform(get(API)).andExpect(status().isForbidden());
    }

    private void evict() {
        userPermissions.evictPermissionDefinitions(getTestTenant().getId());
        userPermissions.evictRoleUsers(getTestTenant().getId(), getTestRole().getId());
    }

    private CloudConfigResponse find(String provider) {
        if (!MetaContext.exists()) applyTestMetaContext();
        return configs.listConfigs("tenant").stream().filter(c -> provider.equals(c.getProviderCode())).findFirst().orElseThrow();
    }

    private CloudConfigSaveRequest request(String type, String provider) {
        CloudConfigSaveRequest request = new CloudConfigSaveRequest();
        request.setConfigLevel("tenant");
        request.setServiceType(type);
        request.setProviderCode(provider);
        request.setConfig("{\"apiKey\":\"test-secret\",\"apiFormat\":\"chat_completions\"}");
        request.setEnabled(false);
        request.setPriority(1);
        return request;
    }

    private String payload(String type, String level, String provider, String pid, int priority) throws Exception {
        var payload = new java.util.HashMap<String, Object>(Map.of("serviceType", type, "configLevel", level,
                "providerCode", provider, "config", "{\"apiKey\":\"test-secret\"}", "enabled", false, "priority", priority));
        if (pid != null) payload.put("pid", pid);
        return json.writeValueAsString(payload);
    }

    private MockMvc mvc() {
        Filter context = (request, response, chain) -> {
            try {
                applyTestMetaContext();
                CustomUserDetails user = new CustomUserDetails(getTestUser().getUserName(), "test-password",
                        getTestUser().getId(), getTestUser().getPid(), AuthorityUtils.NO_AUTHORITIES,
                        true, true, true, true);
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
                chain.doFilter(request, response);
            } finally {
                MetaContext.clear();
                SecurityContextHolder.clearContext();
            }
        };
        return MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilter(context, "/*").build();
    }
}
