package com.auraboot.framework.decision.model;

import com.auraboot.framework.decision.entity.DecisionRolloutPolicyEntity;
import com.auraboot.framework.decision.entity.DrtVersionEntity;

public record DecisionRolloutSelection(
        DecisionRolloutPolicyEntity policy,
        DrtVersionEntity selectedVersion,
        DecisionRolloutArm arm,
        int bucket,
        String routingKey
) {}
