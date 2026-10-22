package com.auraboot.framework.agent.triage;

import java.util.List;
import java.util.Set;

public record TriageVerdict(
        TriageBucket bucket,
        double confidence,                // [0,1]
        List<String> reasonCodes,         // explainability tags from the rule that fired
        Set<String> allowedReadOnlyTools  // populated only when bucket=CONTEXTUAL_ANSWER
) {}
