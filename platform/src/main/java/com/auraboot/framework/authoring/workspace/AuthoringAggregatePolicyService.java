package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.BoundaryDecision;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.PublishPolicy;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.RiskLevel;
import com.auraboot.framework.authoring.policy.AuthoringPolicyContracts.Route;
import com.auraboot.framework.authoring.workspace.AuthoringChangeSetSplitter.ChangeItem;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.AggregatePolicy;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceRepository.WorkspaceRow;
import org.springframework.stereotype.Component;

import java.util.List;

/** Monotonically raises aggregate review requirements as draft changes accumulate. */
@Component
public class AuthoringAggregatePolicyService {

    public AggregatePolicy aggregate(WorkspaceRow row, BoundaryDecision decision) {
        return aggregate(row, List.of(decision));
    }

    public AggregatePolicy aggregate(WorkspaceRow row, List<BoundaryDecision> decisions) {
        RiskLevel risk = RiskLevel.valueOf(row.riskLevel());
        Route route = Route.valueOf(row.route());
        PublishPolicy publishPolicy = PublishPolicy.valueOf(row.publishPolicy());
        for (BoundaryDecision decision : decisions) {
            risk = maxRisk(risk, decision.risk());
            route = maxRoute(route, decision.route());
            publishPolicy = maxPublishPolicy(publishPolicy, decision.publishPolicy());
        }
        return new AggregatePolicy(
                risk.name(),
                route.name(),
                publishPolicy.name(),
                approvalState(row, publishPolicy));
    }

    public AggregatePolicy aggregateItems(List<ChangeItem> items) {
        RiskLevel risk = RiskLevel.L0;
        Route route = Route.INLINE;
        PublishPolicy publishPolicy = PublishPolicy.DIRECT_ALLOWED;
        for (ChangeItem item : items) {
            risk = maxRisk(risk, RiskLevel.valueOf(item.riskLevel()));
            route = maxRoute(route, Route.valueOf(item.route()));
            publishPolicy = maxPublishPolicy(
                    publishPolicy, PublishPolicy.valueOf(item.publishPolicy()));
        }
        return new AggregatePolicy(
                risk.name(), route.name(), publishPolicy.name(),
                publishPolicy == PublishPolicy.DIRECT_ALLOWED ? "NOT_REQUIRED" : "PENDING");
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
