package com.auraboot.framework.behavior.outcome;

import java.util.List;
import java.util.Map;

/** Locks and reads admitted execution provenance in the command transaction. */
public interface AnalyticsCommandOriginLookup {
    List<Map<String, Object>> find(Long tenantId, String runPid);
}
