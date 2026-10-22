package com.auraboot.framework.user.mapper;

import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserMapperAuditDisplayIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private UserMapper userMapper;

    @Test
    void auditDisplayBatchLookupIsScopedToActiveTenantMembership() {
        List<Map<String, Object>> currentTenant = userMapper.findDisplayNamesByIdsInTenant(
                getTestTenant().getId(),
                List.of(getTestUser().getId()));

        assertThat(currentTenant).hasSize(1);
        assertThat(currentTenant.getFirst())
                .containsEntry("id", getTestUser().getId())
                .containsKey("display_name");
        assertThat(String.valueOf(currentTenant.getFirst().get("display_name"))).isNotBlank();

        List<Map<String, Object>> unrelatedTenant = userMapper.findDisplayNamesByIdsInTenant(
                getTestTenant().getId() + 1_000_000_000L,
                List.of(getTestUser().getId()));

        assertThat(unrelatedTenant).isEmpty();
    }
}
