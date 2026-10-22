package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.ToolAclChecker;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-29: Tool ACL 5-dimensional authorisation matcher.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("ToolAclChecker (PR-29)")
class ToolAclIntegrationTest extends BaseIntegrationTest {

    @Autowired private ToolAclChecker checker;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_150_000L + System.nanoTime() % 100_000;
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_tool_acl WHERE tenant_id = ?", tenantId);
    }

    private String insertRule(String profileId, String channel, String runKind,
                              String pattern, String effect, int priority) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_tool_acl " +
                        "(pid, tenant_id, profile_id, channel, run_kind, tool_ref_pattern, " +
                        " effect, priority, active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE, NOW(), NOW())",
                pid, tenantId, profileId, channel, runKind, pattern, effect, priority);
        return pid;
    }

    // =========================================================================
    // Progressive fail-secure
    // =========================================================================

    @Test
    @DisplayName("tenant with NO rules → permitted (pre-ACL behavior; opt-in lockdown)")
    void empty_rule_set_allows() {
        ToolAclChecker.Decision d = checker.check(tenantId, null, "web", "interactive", "cmd_anything");
        assertThat(d.isAllowed()).isTrue();
        assertThat(d.getReason()).isEqualTo("no_acl_configured_for_tenant");
    }

    @Test
    @DisplayName("as soon as the first rule exists, no-match defaults to DENY (fail-secure)")
    void first_rule_flips_to_fail_secure() {
        insertRule(null, null, null, "nq_crm_*", "allow", 100);
        // Request for a tool not matching any rule → deny
        ToolAclChecker.Decision d = checker.check(tenantId, null, "web", "interactive", "cmd_create_lead");
        assertThat(d.isAllowed()).isFalse();
        assertThat(d.getReason()).isEqualTo("no_matching_rule");
    }

    // =========================================================================
    // Matching
    // =========================================================================

    @Test
    @DisplayName("exact tool_ref match → rule applies")
    void exact_pattern_matches() {
        String rule = insertRule(null, null, null, "cmd_create_lead", "allow", 100);
        ToolAclChecker.Decision d = checker.check(tenantId, null, "web", "interactive", "cmd_create_lead");
        assertThat(d.isAllowed()).isTrue();
        assertThat(d.getMatchedRulePid()).isEqualTo(rule);
    }

    @Test
    @DisplayName("trailing-* wildcard matches by prefix")
    void wildcard_matches_prefix() {
        insertRule(null, null, null, "cmd_*", "allow", 100);
        assertThat(checker.check(tenantId, null, "web", "interactive", "cmd_create_lead").isAllowed()).isTrue();
        assertThat(checker.check(tenantId, null, "web", "interactive", "cmd_update_oppty").isAllowed()).isTrue();
        // non-cmd prefix does not match — falls through to deny
        assertThat(checker.check(tenantId, null, "web", "interactive", "nq_crm_list").isAllowed()).isFalse();
    }

    @Test
    @DisplayName("NULL dimension on a rule means 'match any' on that axis")
    void null_dimension_is_wildcard() {
        // Rule has profile_id=NULL → matches any profile
        insertRule(null, "web", "interactive", "nq_*", "allow", 100);
        ToolAclChecker.Decision d = checker.check(tenantId, "prof_sales", "web", "interactive", "nq_x");
        assertThat(d.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("non-null rule dimension must equal request dimension")
    void non_null_dim_requires_match() {
        insertRule("prof_sales", null, null, "nq_*", "allow", 100);
        // profile doesn't match → rule doesn't apply → fall through to deny
        assertThat(checker.check(tenantId, "prof_service", "web", "interactive", "nq_x").isAllowed()).isFalse();
        assertThat(checker.check(tenantId, "prof_sales", "web", "interactive", "nq_x").isAllowed()).isTrue();
    }

    // =========================================================================
    // Priority + effect ordering
    // =========================================================================

    @Test
    @DisplayName("higher priority wins — deny@200 beats allow@100 for the same tool")
    void higher_priority_wins() {
        insertRule(null, null, null, "cmd_*", "allow", 100);
        insertRule(null, null, null, "cmd_dangerous", "deny", 200);
        ToolAclChecker.Decision d = checker.check(tenantId, null, "web", "interactive", "cmd_dangerous");
        assertThat(d.isAllowed()).isFalse();
        assertThat(d.getMatchedPriority()).isEqualTo(200);
    }

    @Test
    @DisplayName("same priority deny wins over allow")
    void same_priority_deny_wins() {
        insertRule(null, null, null, "cmd_*", "allow", 100);
        insertRule(null, null, null, "cmd_*", "deny", 100);
        ToolAclChecker.Decision d = checker.check(tenantId, null, "web", "interactive", "cmd_any");
        assertThat(d.isAllowed()).isFalse();
    }

    // =========================================================================
    // Active flag
    // =========================================================================

    @Test
    @DisplayName("inactive rule is ignored (but other active rules still determine behaviour)")
    void inactive_rule_ignored() {
        String ruleA = insertRule(null, null, null, "cmd_*", "allow", 100);
        jdbc.update("UPDATE ab_agent_tool_acl SET active = FALSE WHERE pid = ?", ruleA);
        // With no active rules the progressive permit kicks back in.
        ToolAclChecker.Decision d = checker.check(tenantId, null, "web", "interactive", "cmd_x");
        assertThat(d.isAllowed()).isTrue();
        assertThat(d.getReason()).isEqualTo("no_acl_configured_for_tenant");
    }

    // =========================================================================
    // Input hygiene
    // =========================================================================

    @Test
    @DisplayName("missing tenantId or toolRef fail-secures immediately")
    void missing_dimension_denied() {
        assertThat(checker.check(null, null, null, null, "cmd_x").isAllowed()).isFalse();
        assertThat(checker.check(tenantId, null, null, null, null).isAllowed()).isFalse();
        assertThat(checker.check(tenantId, null, null, null, "").isAllowed()).isFalse();
    }

    @Test
    @DisplayName("tenant isolation — rules from other tenants are not consulted")
    void tenant_isolation() {
        insertRule(null, null, null, "cmd_*", "allow", 100);

        Long otherTenant = tenantId + 1_000_000;
        // Other tenant has no rules → progressive permit applies to it
        ToolAclChecker.Decision d = checker.check(otherTenant, null, "web", "interactive", "cmd_x");
        assertThat(d.isAllowed()).isTrue();
        assertThat(d.getReason()).isEqualTo("no_acl_configured_for_tenant");

        // Our tenant still uses its own rules.
        assertThat(checker.check(tenantId, null, "web", "interactive", "cmd_x").isAllowed()).isTrue();
        assertThat(checker.check(tenantId, null, "web", "interactive", "nq_x").isAllowed()).isFalse();
    }
}
