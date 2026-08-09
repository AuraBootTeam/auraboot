package com.auraboot.framework.authoring.policy;

import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.BoundaryDecision;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.BoundaryEvaluationInput;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.CapabilityManifest;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PatchOperation;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PublishPolicy;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.Reason;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.ResourceScope;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.RiskLevel;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.Route;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.SecurityImpact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthoringBoundaryPolicyServiceTest {

    private CoreAuthoringCapabilityRegistry registry;
    private AuthoringBoundaryPolicyService service;

    @BeforeEach
    void setUp() {
        registry = new CoreAuthoringCapabilityRegistry();
        service = new AuthoringBoundaryPolicyService(registry);
    }

    @Test
    void lowRiskLayoutPropertyRoutesInline() {
        BoundaryDecision decision = evaluate("table", "/layout/span", ResourceScope.CURRENT_PAGE,
                SecurityImpact.NONE, true, true, currentChecksum("table"));

        assertThat(decision.route()).isEqualTo(Route.INLINE);
        assertThat(decision.risk()).isEqualTo(RiskLevel.L0);
        assertThat(decision.publishPolicy()).isEqualTo(PublishPolicy.DIRECT_ALLOWED);
        assertThat(decision.reason()).isEqualTo(Reason.CAPABILITY_ALLOWED);
    }

    @Test
    void defaultFilterRemainsInlineButRequiresReviewAndRolePreview() {
        BoundaryDecision decision = evaluate("table", "/props/defaultFilter", ResourceScope.CURRENT_PAGE,
                SecurityImpact.NONE, true, true, currentChecksum("table"));

        assertThat(decision.route()).isEqualTo(Route.GUIDED_INLINE);
        assertThat(decision.risk()).isEqualTo(RiskLevel.L2);
        assertThat(decision.publishPolicy()).isEqualTo(PublishPolicy.REQUIRED_REVIEW);
        assertThat(decision.rolePreviewRequired()).isTrue();
    }

    @Test
    void dataBindingAlwaysHandsOffToStudio() {
        BoundaryDecision decision = evaluate("table", "/dataSource", ResourceScope.CURRENT_PAGE,
                SecurityImpact.NONE, true, true, currentChecksum("table"));

        assertThat(decision.route()).isEqualTo(Route.HANDOFF_STUDIO);
        assertThat(decision.risk()).isEqualTo(RiskLevel.L3);
        assertThat(decision.reason()).isEqualTo(Reason.BUSINESS_SEMANTIC);
    }

    @Test
    void staleManifestFailsClosed() {
        BoundaryDecision decision = evaluate("field", "/title", ResourceScope.CURRENT_PAGE,
                SecurityImpact.NONE, true, true, "stale-checksum");

        assertThat(decision.route()).isEqualTo(Route.DENY);
        assertThat(decision.reason()).isEqualTo(Reason.MANIFEST_STALE);
        assertThat(decision.manifestChecksum()).isEqualTo(currentChecksum("field"));
    }

    @Test
    void unknownPropertyFailsClosed() {
        BoundaryDecision decision = evaluate("field", "/props/permission", ResourceScope.CURRENT_PAGE,
                SecurityImpact.NONE, true, true, currentChecksum("field"));

        assertThat(decision.route()).isEqualTo(Route.DENY);
        assertThat(decision.reason()).isEqualTo(Reason.CAPABILITY_UNKNOWN);
    }

    @Test
    void invalidProtectedActionSemanticFailsClosed() {
        BoundaryDecision decision = evaluate("action", "/props/label", ResourceScope.CURRENT_PAGE,
                SecurityImpact.NONE, true, false, currentChecksum("action"));

        assertThat(decision.route()).isEqualTo(Route.DENY);
        assertThat(decision.reason()).isEqualTo(Reason.PROTECTED_SEMANTIC_INVALID);
    }

    @Test
    void validProtectedActionPresentationStaysGuidedInlineAndRequiresReview() {
        BoundaryDecision decision = evaluate("action", "/props/label", ResourceScope.CURRENT_PAGE,
                SecurityImpact.NONE, true, true, currentChecksum("action"));

        assertThat(decision.route()).isEqualTo(Route.GUIDED_INLINE);
        assertThat(decision.risk()).isEqualTo(RiskLevel.L2);
        assertThat(decision.publishPolicy()).isEqualTo(PublishPolicy.REQUIRED_REVIEW);
    }

    @Test
    void pageLocalCriticalPresentationCannotRequestDirectPublish() {
        for (String path : new String[]{"/props/defaultSort", "/props/defaultFilter"}) {
            BoundaryDecision decision = evaluate("table", path, ResourceScope.CURRENT_PAGE,
                    SecurityImpact.NONE, true, true, currentChecksum("table"));
            assertThat(decision.risk()).as(path).isEqualTo(RiskLevel.L2);
            assertThat(decision.route()).as(path).isEqualTo(Route.GUIDED_INLINE);
            assertThat(decision.publishPolicy()).as(path)
                    .isEqualTo(PublishPolicy.REQUIRED_REVIEW);
        }
        for (String path : new String[]{"/props/variant", "/props/visibleWhen"}) {
            BoundaryDecision decision = evaluate("action", path, ResourceScope.CURRENT_PAGE,
                    SecurityImpact.NONE, true, true, currentChecksum("action"));
            assertThat(decision.risk()).as(path).isEqualTo(RiskLevel.L2);
            assertThat(decision.publishPolicy()).as(path)
                    .isEqualTo(PublishPolicy.REQUIRED_REVIEW);
        }
    }

    @Test
    void sharedResourceAndSecurityImpactCannotStayInline() {
        BoundaryDecision shared = evaluate("field", "/title", ResourceScope.SHARED_PAGE,
                SecurityImpact.NONE, true, true, currentChecksum("field"));
        BoundaryDecision security = evaluate("field", "/title", ResourceScope.CURRENT_PAGE,
                SecurityImpact.PRESENT, true, true, currentChecksum("field"));

        assertThat(shared.route()).isEqualTo(Route.HANDOFF_STUDIO);
        assertThat(shared.reason()).isEqualTo(Reason.SHARED_RESOURCE);
        assertThat(security.route()).isEqualTo(Route.HANDOFF_STUDIO);
        assertThat(security.reason()).isEqualTo(Reason.SECURITY_SENSITIVE);
    }

    @Test
    void unknownImpactAndUnknownBlockFailClosed() {
        BoundaryDecision unknownImpact = evaluate("field", "/title", ResourceScope.CURRENT_PAGE,
                SecurityImpact.UNKNOWN, false, true, currentChecksum("field"));
        BoundaryDecision unknownBlock = evaluate("plugin-secret", "/title", ResourceScope.CURRENT_PAGE,
                SecurityImpact.NONE, true, true, "anything");

        assertThat(unknownImpact.reason()).isEqualTo(Reason.IMPACT_UNKNOWN);
        assertThat(unknownImpact.publishPolicy()).isEqualTo(PublishPolicy.DENIED);
        assertThat(unknownBlock.reason()).isEqualTo(Reason.CAPABILITY_UNKNOWN);
        assertThat(unknownBlock.route()).isEqualTo(Route.DENY);
    }

    @Test
    void userPrivatePresentationUsesPersonalizationRouteWithoutChangeSetPublish() {
        BoundaryDecision decision = evaluate("table", "/props/density", ResourceScope.USER_PRIVATE,
                SecurityImpact.NONE, true, true, currentChecksum("table"));

        assertThat(decision.route()).isEqualTo(Route.PERSONALIZE);
        assertThat(decision.publishPolicy()).isEqualTo(PublishPolicy.DIRECT_ALLOWED);
    }

    private BoundaryDecision evaluate(
            String blockType,
            String propertyPath,
            ResourceScope scope,
            SecurityImpact securityImpact,
            boolean impactKnown,
            boolean protectedSemanticValid,
            String checksum) {
        return service.evaluate(new BoundaryEvaluationInput(blockType, propertyPath,
                PatchOperation.REPLACE, scope, securityImpact, impactKnown,
                protectedSemanticValid, checksum));
    }

    private String currentChecksum(String blockType) {
        return registry.find(blockType).map(CapabilityManifest::checksum).orElseThrow();
    }
}
