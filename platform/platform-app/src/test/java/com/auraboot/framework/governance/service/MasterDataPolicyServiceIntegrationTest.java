package com.auraboot.framework.governance.service;

import com.auraboot.framework.governance.dto.PolicyCreateDTO;
import com.auraboot.framework.governance.dto.PolicyResponse;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for MasterDataPolicyService.
 *
 * <ul>
 *   <li>GP-01: upsertPolicy creates a new policy</li>
 *   <li>GP-02: upsertPolicy updates existing policy for same model</li>
 *   <li>GP-03: listPolicies returns all tenant policies</li>
 *   <li>GP-04: getPolicy returns policy for model code</li>
 *   <li>GP-05: getPolicy returns null for unknown model</li>
 *   <li>GP-06: deletePolicy removes the policy</li>
 *   <li>GP-07: deletePolicy unknown pid throws</li>
 *   <li>GP-08: requiresApproval returns true when configured</li>
 *   <li>GP-09: requiresApproval returns false for unknown model</li>
 *   <li>GP-10: requiresAutoSnapshot returns true when configured</li>
 *   <li>GP-11: upsertPolicy without modelCode throws</li>
 * </ul>
 */
@Slf4j
@DisplayName("MasterDataPolicyService Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MasterDataPolicyServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MasterDataPolicyService policyService;

    private final String runId = String.valueOf(System.currentTimeMillis() % 100_000_000L);
    private final String modelCode = "gp_model_" + runId;

    private String policyPid;

    @Test
    @Order(1)
    @DisplayName("GP-01: upsertPolicy creates a new policy")
    void GP_01_createPolicy() {
        PolicyCreateDTO dto = new PolicyCreateDTO();
        dto.setModelCode(modelCode);
        dto.setRequireApproval(true);
        dto.setAutoSnapshot(true);
        dto.setAllowedEditors(List.of("admin", "editor"));

        PolicyResponse result = policyService.upsertPolicy(dto, testTenant.getId());

        assertThat(result).isNotNull();
        assertThat(result.getPid()).isNotBlank();
        assertThat(result.getModelCode()).isEqualTo(modelCode);
        assertThat(result.getRequireApproval()).isTrue();
        assertThat(result.getAutoSnapshot()).isTrue();

        policyPid = result.getPid();
        log.info("GP-01: created policy pid={} for model={}", policyPid, modelCode);
    }

    @Test
    @Order(2)
    @DisplayName("GP-02: upsertPolicy updates existing policy for same model")
    void GP_02_updatePolicy() {
        PolicyCreateDTO dto = new PolicyCreateDTO();
        dto.setModelCode(modelCode);
        dto.setRequireApproval(false);
        dto.setAutoSnapshot(true);

        PolicyResponse result = policyService.upsertPolicy(dto, testTenant.getId());

        assertThat(result.getRequireApproval()).isFalse();
        assertThat(result.getAutoSnapshot()).isTrue();
        // PID should remain the same (same record updated)
        assertThat(result.getPid()).isEqualTo(policyPid);
    }

    @Test
    @Order(3)
    @DisplayName("GP-03: listPolicies returns all tenant policies")
    void GP_03_listPolicies() {
        List<PolicyResponse> policies = policyService.listPolicies(testTenant.getId());

        assertThat(policies).isNotEmpty();
        assertThat(policies).anyMatch(p -> p.getModelCode().equals(modelCode));
    }

    @Test
    @Order(4)
    @DisplayName("GP-04: getPolicy returns policy for model code")
    void GP_04_getPolicy() {
        PolicyResponse result = policyService.getPolicy(modelCode, testTenant.getId());

        assertThat(result).isNotNull();
        assertThat(result.getModelCode()).isEqualTo(modelCode);
    }

    @Test
    @Order(5)
    @DisplayName("GP-05: getPolicy returns null for unknown model")
    void GP_05_getPolicy_unknownModel() {
        PolicyResponse result = policyService.getPolicy("unknown_model_" + runId, testTenant.getId());
        assertThat(result).isNull();
    }

    @Test
    @Order(6)
    @DisplayName("GP-06: requiresApproval returns false (updated in GP-02)")
    void GP_06_requiresApproval() {
        // GP-02 set requireApproval=false
        boolean result = policyService.requiresApproval(modelCode, testTenant.getId());
        assertThat(result).isFalse();
    }

    @Test
    @Order(7)
    @DisplayName("GP-07: requiresApproval returns false for unknown model")
    void GP_07_requiresApproval_unknownModel() {
        boolean result = policyService.requiresApproval("unknown_model_" + runId, testTenant.getId());
        assertThat(result).isFalse();
    }

    @Test
    @Order(8)
    @DisplayName("GP-08: requiresAutoSnapshot returns true when configured")
    void GP_08_requiresAutoSnapshot() {
        boolean result = policyService.requiresAutoSnapshot(modelCode, testTenant.getId());
        assertThat(result).isTrue();
    }

    @Test
    @Order(9)
    @DisplayName("GP-09: deletePolicy removes the policy")
    void GP_09_deletePolicy() {
        assertThat(policyPid).as("policyPid from GP-01").isNotBlank();

        policyService.deletePolicy(policyPid, testTenant.getId());

        PolicyResponse result = policyService.getPolicy(modelCode, testTenant.getId());
        assertThat(result).isNull();
    }

    @Test
    @Order(10)
    @DisplayName("GP-10: deletePolicy unknown pid throws")
    void GP_10_deletePolicy_unknownPid() {
        assertThatThrownBy(() -> policyService.deletePolicy("no-such-pid-" + runId, testTenant.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @Order(11)
    @DisplayName("GP-11: upsertPolicy without modelCode throws")
    void GP_11_upsertPolicy_noModelCode() {
        PolicyCreateDTO dto = new PolicyCreateDTO();
        dto.setRequireApproval(true);

        assertThatThrownBy(() -> policyService.upsertPolicy(dto, testTenant.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelCode");
    }
}
