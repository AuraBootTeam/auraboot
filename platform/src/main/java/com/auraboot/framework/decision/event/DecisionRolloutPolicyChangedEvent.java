package com.auraboot.framework.decision.event;

import com.auraboot.framework.event.AuraEvent;
import lombok.Getter;

import java.util.Map;

/**
 * Published after a rollout policy serving state changes.
 */
@Getter
public class DecisionRolloutPolicyChangedEvent extends AuraEvent {

    public static final String EVENT_TYPE = "decision.rollout.policy.changed";

    private final String policyPid;
    private final String decisionCode;

    public DecisionRolloutPolicyChangedEvent(Long tenantId, String policyPid, String decisionCode) {
        super(tenantId, EVENT_TYPE, "ab_drt_rollout_policy", policyPid,
                Map.of("policyPid", policyPid, "decisionCode", decisionCode));
        this.policyPid = policyPid;
        this.decisionCode = decisionCode;
    }
}
