package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.BoundaryDecision;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PublishPolicy;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.RiskLevel;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.Route;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.AggregatePolicy;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.WorkspaceRow;
import org.springframework.stereotype.Component;

/** Monotonically raises aggregate review requirements as draft changes accumulate. */
@Component
public class AuthoringAggregatePolicyService {

    public AggregatePolicy aggregate(WorkspaceRow row, BoundaryDecision decision) {
        RiskLevel risk = maxRisk(RiskLevel.valueOf(row.riskLevel()), decision.risk());
        Route route = maxRoute(Route.valueOf(row.route()), decision.route());
        PublishPolicy publishPolicy = maxPublishPolicy(
                PublishPolicy.valueOf(row.publishPolicy()), decision.publishPolicy());
        return new AggregatePolicy(
                risk.name(),
                route.name(),
                publishPolicy.name(),
                approvalState(row, publishPolicy));
    }

    private String approvalState(WorkspaceRow row, PublishPolicy publishPolicy) {
        if ("APPROVED".equals(row.approvalState())) {
            return "STALE";
        }
        return publishPolicy == PublishPolicy.DIRECT_ALLOWED ? "NOT_REQUIRED" : "PENDING";
    }

    private RiskLevel maxRisk(RiskLevel left, RiskLevel right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    private Route maxRoute(Route left, Route right) {
        return routeWeight(left) >= routeWeight(right) ? left : right;
    }

    private int routeWeight(Route route) {
        return switch (route) {
            case PERSONALIZE -> 0;
            case INLINE -> 1;
            case GUIDED_INLINE -> 2;
            case HANDOFF_STUDIO -> 3;
            case DENY -> 4;
        };
    }

    private PublishPolicy maxPublishPolicy(PublishPolicy left, PublishPolicy right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }
}
