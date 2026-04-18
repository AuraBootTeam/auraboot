package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.CapabilityRouter;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: CapabilityRouter returns non-empty skill lists for the
 * seeded platform built-in capabilities (crm / pm / generic). Spec §5.6.
 *
 * Previously the seed had empty skills arrays so the router always returned
 * []. GroundingService.resolveCandidateSkills then fell through to its
 * hard-coded dsl.query/dsl.command fallback — functional but bypassing the
 * whole Capability layer. This test pins the data: if someone accidentally
 * empties the skills seed again, the test fails immediately.
 */
@DisplayName("CapabilityRouter — built-in platform capabilities")
class CapabilityRouterIntegrationTest extends BaseIntegrationTest {

    @Autowired private CapabilityRouter router;

    @Test
    @DisplayName("query intent on crm_* model routes to dsl.query")
    void crm_query_routes() {
        List<String> skills = router.route(testTenant.getId(), "query", "crm_account");
        assertThat(skills).contains("dsl.query");
    }

    @Test
    @DisplayName("create intent on crm_* model routes to dsl.command")
    void crm_manage_routes() {
        List<String> skills = router.route(testTenant.getId(), "create", "crm_lead");
        assertThat(skills).contains("dsl.command");
    }

    @Test
    @DisplayName("analyze intent (read family) on crm_* routes to dsl.query")
    void analyze_intent_routes_to_query() {
        List<String> skills = router.route(testTenant.getId(), "analyze", "crm_opportunity");
        assertThat(skills).contains("dsl.query");
    }

    @Test
    @DisplayName("unknown domain falls back to generic capabilities")
    void unknown_domain_falls_back_to_generic() {
        // No domain-specific capability for wf_* — generic.query should match via "*" pattern.
        List<String> skills = router.route(testTenant.getId(), "query", "wf_some_model");
        assertThat(skills).as("generic.query matches any object").contains("dsl.query");
    }

    @Test
    @DisplayName("null objectCode returns empty (no capability can match)")
    void null_object_returns_empty() {
        assertThat(router.route(testTenant.getId(), "query", null)).isEmpty();
    }
}
