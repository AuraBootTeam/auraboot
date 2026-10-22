package com.auraboot.framework.integration.security;

import com.auraboot.framework.application.web.filter.JwtAuthenticationFilter;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.security.rbac.RbacAccessMatrix;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * REG-3 regression guard (DDR-2026-06-30) — the anonymous-access half.
 *
 * <p>{@link AgentDiscoveryTenantScopeIT} already locks that A2A discovery is tenant-scoped for an
 * <em>authenticated</em> caller. This IT locks the other half of REG-3: the {@code /.well-known/agent*}
 * endpoints were removed from the security WhiteList (OSS #1147) — previously they were anonymous,
 * leaking every tenant's agent metadata (name/description/skills) to unauthenticated callers. An
 * anonymous request must now be rejected with 401.
 *
 * <p><b>Why the real filter, not a direct controller call.</b> Unlike the REG-2/5-6 guards (whose
 * 403 is produced by an in-controller / interceptor check), REG-3's deny is produced by the real
 * security filter: {@link JwtAuthenticationFilter} short-circuits a missing {@code Authorization}
 * header with 401 <em>before</em> the request reaches the controller, and its own whitelist check
 * ({@code shouldNotFilter}) is what would let the endpoint back in if it were ever re-whitelisted.
 * So this test drives the request through the actual {@code JwtAuthenticationFilter} bean rather than
 * calling the controller directly — the "real-stack verification" the golden-suite methodology
 * (rbac-golden-and-cross-cutting-regression.md §6, item ⑤) flags as the last untested REG point.
 * ({@code spring-security-test} / {@code springSecurity()} is not a dependency of this project, so
 * the filter is added to MockMvc explicitly — the same idiom as {@code AdminGuardTestSupport}.)
 *
 * <p>The endpoint and expected status are read from the SOT matrix
 * ({@code rbac/rbac-access-matrix.json}, rule {@code REG-3-anon-discovery}) so this A-layer assertion
 * and the declared matrix can never drift apart.
 */
@DisplayName("anonymous A2A discovery is rejected 401 (REG-3)")
class AgentDiscoveryAnonymousAuthIT extends BaseIntegrationTest {

    private static final String RULE = "REG-3-anon-discovery";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JwtAuthenticationFilter jwtAuthenticationFilter;

    private MockMvc mvc;
    private String discoveryEndpoint;
    private int anonymousStatus;

    @BeforeEach
    void setup() {
        // Drive requests through the REAL production auth filter with NO Authorization header, so the
        // request is genuinely anonymous. The filter runs its own whitelist check, then rejects.
        mvc = webAppContextSetup(webApplicationContext)
                .addFilters(jwtAuthenticationFilter)
                .build();

        RbacAccessMatrix.SpecialRule reg3 = RbacAccessMatrix.load().specialRule(RULE);
        discoveryEndpoint = reg3.endpoint();      // /.well-known/agent.json
        anonymousStatus = reg3.anonymousStatus(); // 401
    }

    @Test
    @DisplayName("anonymous GET /.well-known/agent.json → 401 (matrix-declared)")
    void anonymousDiscoveryIndex_rejected() throws Exception {
        mvc.perform(get(discoveryEndpoint))
                .andExpect(status().is(anonymousStatus));
    }

    @Test
    @DisplayName("anonymous GET /.well-known/agent/{code}.json → 401 (same REG-3 surface)")
    void anonymousAgentCard_rejected() throws Exception {
        // The per-agent card lives under the same /.well-known/agent* prefix removed from the
        // WhiteList; an anonymous caller must not resolve any tenant's card either. The agentCode
        // need not exist — auth is rejected before the controller (and any DB lookup) runs.
        mvc.perform(get("/.well-known/agent/any-probe.json"))
                .andExpect(status().is(anonymousStatus));
    }
}
