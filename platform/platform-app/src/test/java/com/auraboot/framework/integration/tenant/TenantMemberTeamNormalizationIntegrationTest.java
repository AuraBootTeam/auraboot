package com.auraboot.framework.integration.tenant;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TenantMember Team Normalization - Integration Tests")
class TenantMemberTeamNormalizationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TenantMemberService tenantMemberService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("updateMember should normalize teamIds into settings.teamIds")
    void updateMemberNormalizesTeamIds() throws Exception {
        TenantMember member = tenantMemberService.findByTenantIdAndUserId(getTestTenant().getId(), getTestUser().getId());
        member.setSettings("{\"teamIds\":[\"TEAM_A\"],\"profile\":{\"team_id\":\"TEAM_B\"}}");
        member.setExtensions("{\"teams\":\"TEAM_C,TEAM_D\"}");
        member.setPermissions("{\"dataScope\":{\"teamIds\":\"[\\\"TEAM_E\\\",\\\"TEAM_A\\\"]\"}}");
        tenantMemberService.updateMember(member);

        TenantMember updated = tenantMemberService.findByTenantIdAndUserId(getTestTenant().getId(), getTestUser().getId());
        JsonNode settings = objectMapper.readTree(updated.getSettings());
        JsonNode teamIds = settings.path("teamIds");
        assertTrue(teamIds.isArray());
        assertEquals(5, teamIds.size());
        assertEquals("team_a", teamIds.get(0).asText());
        assertEquals("team_b", teamIds.get(1).asText());
        assertEquals("team_c", teamIds.get(2).asText());
        assertEquals("team_d", teamIds.get(3).asText());
        assertEquals("team_e", teamIds.get(4).asText());
    }

    @Test
    @DisplayName("updateMember should reject invalid JSON in settings/extensions/permissions")
    void updateMemberRejectsInvalidJson() {
        TenantMember member = tenantMemberService.findByTenantIdAndUserId(getTestTenant().getId(), getTestUser().getId());
        member.setSettings("{invalid-json");
        BusinessException exception = assertThrows(BusinessException.class, () -> tenantMemberService.updateMember(member));
        assertTrue(exception.getMessage().contains("Invalid tenant_member.settings JSON"));
    }
}
