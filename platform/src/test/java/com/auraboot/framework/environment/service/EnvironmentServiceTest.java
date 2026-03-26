package com.auraboot.framework.environment.service;

import com.auraboot.framework.environment.dao.entity.Environment;
import com.auraboot.framework.environment.dao.mapper.EnvironmentMapper;
import com.auraboot.framework.environment.dto.*;
import com.auraboot.framework.environment.service.impl.EnvironmentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EnvironmentServiceImpl.
 * Covers CRUD operations, export/import, diff, and error paths.
 */
@ExtendWith(MockitoExtension.class)
class EnvironmentServiceTest {

    @Mock
    private EnvironmentMapper environmentMapper;

    @InjectMocks
    private EnvironmentServiceImpl environmentService;

    private static final Long TENANT_ID = 100L;
    private static final Long USER_ID = 1L;

    private EnvironmentRequest devRequest;
    private EnvironmentRequest prodRequest;

    @BeforeEach
    void setUp() {
        devRequest = new EnvironmentRequest();
        devRequest.setCode("dev");
        devRequest.setName("Development");
        devRequest.setDescription("Development environment");
        devRequest.setApiBaseUrl("http://localhost:6443");
        devRequest.setDbConnectionInfo(Map.of("host", "localhost", "port", 5432, "database", "aura_dev"));
        devRequest.setIsDefault(true);
        devRequest.setSortOrder(0);

        prodRequest = new EnvironmentRequest();
        prodRequest.setCode("prod");
        prodRequest.setName("Production");
        prodRequest.setApiBaseUrl("https://api.example.com");
        prodRequest.setDbConnectionInfo(Map.of("host", "db.example.com", "port", 5432, "database", "aura_prod"));
        prodRequest.setIsDefault(false);
        prodRequest.setSortOrder(2);
    }

    // ---- Create ----

    @Test
    void create_success() {
        when(environmentMapper.findByTenantAndCode(TENANT_ID, "dev")).thenReturn(null);
        when(environmentMapper.clearDefaultForTenant(TENANT_ID)).thenReturn(0);
        when(environmentMapper.insert(any(Environment.class))).thenReturn(1);

        EnvironmentResponse resp = environmentService.create(devRequest, TENANT_ID, USER_ID);

        assertThat(resp.getCode()).isEqualTo("dev");
        assertThat(resp.getName()).isEqualTo("Development");
        assertThat(resp.getApiBaseUrl()).isEqualTo("http://localhost:6443");
        assertThat(resp.getIsDefault()).isTrue();
        assertThat(resp.getPid()).isNotBlank();

        ArgumentCaptor<Environment> captor = ArgumentCaptor.forClass(Environment.class);
        verify(environmentMapper).insert(captor.capture());
        Environment saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(saved.getCreatedBy()).isEqualTo(USER_ID);
        assertThat(saved.getDeletedFlag()).isFalse();
    }

    @Test
    void create_duplicateCode_throwsException() {
        Environment existing = buildEnvironment("dev", "Dev", TENANT_ID);
        when(environmentMapper.findByTenantAndCode(TENANT_ID, "dev")).thenReturn(existing);

        assertThatThrownBy(() -> environmentService.create(devRequest, TENANT_ID, USER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void create_nonDefault_doesNotClearOtherDefaults() {
        devRequest.setIsDefault(false);
        when(environmentMapper.findByTenantAndCode(TENANT_ID, "dev")).thenReturn(null);
        when(environmentMapper.insert(any(Environment.class))).thenReturn(1);

        environmentService.create(devRequest, TENANT_ID, USER_ID);

        verify(environmentMapper, never()).clearDefaultForTenant(any());
    }

    // ---- List ----

    @Test
    void listAll_returnsSortedResults() {
        List<Environment> envs = List.of(
                buildEnvironment("dev", "Development", TENANT_ID),
                buildEnvironment("prod", "Production", TENANT_ID)
        );
        when(environmentMapper.findAllByTenant(TENANT_ID)).thenReturn(envs);

        List<EnvironmentResponse> result = environmentService.listAll(TENANT_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getCode()).isEqualTo("dev");
        assertThat(result.get(1).getCode()).isEqualTo("prod");
    }

    // ---- Get ----

    @Test
    void getByPid_notFound_throwsException() {
        when(environmentMapper.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> environmentService.getByPid("nonexistent", TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    // ---- Update ----

    @Test
    void update_success() {
        Environment existing = buildEnvironment("dev", "Dev", TENANT_ID);
        existing.setPid("pid123");
        when(environmentMapper.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(existing);
        when(environmentMapper.updateById(any(Environment.class))).thenReturn(1);

        EnvironmentRequest updateReq = new EnvironmentRequest();
        updateReq.setCode("dev"); // same code
        updateReq.setName("Dev Updated");
        updateReq.setApiBaseUrl("http://new-url:6443");
        updateReq.setIsDefault(false);

        EnvironmentResponse resp = environmentService.update("pid123", updateReq, TENANT_ID, USER_ID);

        assertThat(resp.getName()).isEqualTo("Dev Updated");
        assertThat(resp.getApiBaseUrl()).isEqualTo("http://new-url:6443");
    }

    @Test
    void update_codeChange_duplicateCheck() {
        Environment existing = buildEnvironment("dev", "Dev", TENANT_ID);
        existing.setPid("pid123");
        when(environmentMapper.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(existing);

        Environment duplicate = buildEnvironment("staging", "Staging", TENANT_ID);
        when(environmentMapper.findByTenantAndCode(TENANT_ID, "staging")).thenReturn(duplicate);

        EnvironmentRequest updateReq = new EnvironmentRequest();
        updateReq.setCode("staging"); // try to change to existing code
        updateReq.setName("Dev");

        assertThatThrownBy(() -> environmentService.update("pid123", updateReq, TENANT_ID, USER_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    // ---- Delete ----

    @Test
    void delete_success() {
        Environment existing = buildEnvironment("dev", "Dev", TENANT_ID);
        existing.setPid("pid_del");
        when(environmentMapper.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(existing);
        when(environmentMapper.updateById(any(Environment.class))).thenReturn(1);

        environmentService.delete("pid_del", TENANT_ID);

        ArgumentCaptor<Environment> captor = ArgumentCaptor.forClass(Environment.class);
        verify(environmentMapper).updateById(captor.capture());
        assertThat(captor.getValue().getDeletedFlag()).isTrue();
    }

    // ---- Export ----

    @Test
    void exportConfig_success() {
        Environment env = buildEnvironment("dev", "Development", TENANT_ID);
        env.setApiBaseUrl("http://localhost:6443");
        env.setDbConnectionInfo(Map.of("host", "localhost"));
        when(environmentMapper.findByTenantAndCode(TENANT_ID, "dev")).thenReturn(env);

        EnvironmentExportData data = environmentService.exportConfig("dev", TENANT_ID);

        assertThat(data.getCode()).isEqualTo("dev");
        assertThat(data.getName()).isEqualTo("Development");
        assertThat(data.getApiBaseUrl()).isEqualTo("http://localhost:6443");
        assertThat(data.getExportedAt()).isNotNull();
    }

    @Test
    void exportConfig_notFound_throwsException() {
        when(environmentMapper.findByTenantAndCode(TENANT_ID, "nope")).thenReturn(null);

        assertThatThrownBy(() -> environmentService.exportConfig("nope", TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    // ---- Import ----

    @Test
    void importConfig_updatesExistingEnvironment() {
        Environment existing = buildEnvironment("staging", "Old Staging", TENANT_ID);
        when(environmentMapper.findByTenantAndCode(TENANT_ID, "staging")).thenReturn(existing);
        when(environmentMapper.updateById(any(Environment.class))).thenReturn(1);

        EnvironmentExportData data = new EnvironmentExportData();
        data.setName("New Staging");
        data.setApiBaseUrl("https://staging.example.com");
        data.setIsDefault(false);

        EnvironmentResponse resp = environmentService.importConfig("staging", data, TENANT_ID, USER_ID);

        assertThat(resp.getName()).isEqualTo("New Staging");
        assertThat(resp.getApiBaseUrl()).isEqualTo("https://staging.example.com");
    }

    @Test
    void importConfig_createsNewEnvironment() {
        when(environmentMapper.findByTenantAndCode(TENANT_ID, "qa")).thenReturn(null);
        when(environmentMapper.insert(any(Environment.class))).thenReturn(1);

        EnvironmentExportData data = new EnvironmentExportData();
        data.setName("QA");
        data.setApiBaseUrl("https://qa.example.com");
        data.setIsDefault(false);

        EnvironmentResponse resp = environmentService.importConfig("qa", data, TENANT_ID, USER_ID);

        assertThat(resp.getCode()).isEqualTo("qa");
        assertThat(resp.getName()).isEqualTo("QA");
    }

    // ---- Diff ----

    @Test
    void diff_detectsChanges() {
        Environment dev = buildEnvironment("dev", "Dev", TENANT_ID);
        dev.setApiBaseUrl("http://localhost:6443");
        dev.setDbConnectionInfo(Map.of("host", "localhost", "port", 5432));

        Environment prod = buildEnvironment("prod", "Prod", TENANT_ID);
        prod.setApiBaseUrl("https://api.example.com");
        prod.setDbConnectionInfo(Map.of("host", "db.example.com", "port", 5432, "region", "us-east-1"));

        when(environmentMapper.findByTenantAndCode(TENANT_ID, "dev")).thenReturn(dev);
        when(environmentMapper.findByTenantAndCode(TENANT_ID, "prod")).thenReturn(prod);

        EnvironmentDiffResponse diff = environmentService.diff("dev", "prod", TENANT_ID);

        assertThat(diff.getSourceCode()).isEqualTo("dev");
        assertThat(diff.getTargetCode()).isEqualTo("prod");
        assertThat(diff.getDifferences()).isNotEmpty();

        // apiBaseUrl should be CHANGED
        assertThat(diff.getDifferences())
                .anyMatch(d -> d.getKey().equals("apiBaseUrl") && d.getChangeType().equals("changed"));
        // name should be CHANGED
        assertThat(diff.getDifferences())
                .anyMatch(d -> d.getKey().equals("name") && d.getChangeType().equals("changed"));
        // dbConnectionInfo.host should be CHANGED
        assertThat(diff.getDifferences())
                .anyMatch(d -> d.getKey().equals("dbConnectionInfo.host") && d.getChangeType().equals("changed"));
        // dbConnectionInfo.region should be ADDED (only in target)
        assertThat(diff.getDifferences())
                .anyMatch(d -> d.getKey().equals("dbConnectionInfo.region") && d.getChangeType().equals("added"));
        // dbConnectionInfo.port should NOT appear (same value)
        assertThat(diff.getDifferences())
                .noneMatch(d -> d.getKey().equals("dbConnectionInfo.port"));
    }

    @Test
    void diff_sourceNotFound_throwsException() {
        when(environmentMapper.findByTenantAndCode(TENANT_ID, "nope")).thenReturn(null);

        assertThatThrownBy(() -> environmentService.diff("nope", "prod", TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Source");
    }

    @Test
    void diff_targetNotFound_throwsException() {
        Environment dev = buildEnvironment("dev", "Dev", TENANT_ID);
        when(environmentMapper.findByTenantAndCode(TENANT_ID, "dev")).thenReturn(dev);
        when(environmentMapper.findByTenantAndCode(TENANT_ID, "nope")).thenReturn(null);

        assertThatThrownBy(() -> environmentService.diff("dev", "nope", TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target");
    }

    @Test
    void diff_identicalEnvironments_returnsEmptyDiff() {
        Environment env1 = buildEnvironment("dev", "Dev", TENANT_ID);
        env1.setApiBaseUrl("http://localhost");
        env1.setStatus("active");

        Environment env2 = buildEnvironment("staging", "Dev", TENANT_ID);
        env2.setApiBaseUrl("http://localhost");
        env2.setStatus("active");

        when(environmentMapper.findByTenantAndCode(TENANT_ID, "dev")).thenReturn(env1);
        when(environmentMapper.findByTenantAndCode(TENANT_ID, "staging")).thenReturn(env2);

        EnvironmentDiffResponse diff = environmentService.diff("dev", "staging", TENANT_ID);

        assertThat(diff.getDifferences()).isEmpty();
    }

    // ---- Helpers ----

    private Environment buildEnvironment(String code, String name, Long tenantId) {
        Environment env = new Environment();
        env.setId(System.currentTimeMillis());
        env.setPid("pid_" + code.toUpperCase());
        env.setTenantId(tenantId);
        env.setCode(code);
        env.setName(name);
        env.setStatus("active");
        env.setIsDefault(false);
        env.setDeletedFlag(false);
        env.setSortOrder(0);
        env.setCreatedAt(new Date());
        env.setUpdatedAt(new Date());
        return env;
    }
}
