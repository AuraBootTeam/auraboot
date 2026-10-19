package com.auraboot.framework.behavior.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Owner-scoped execution facts; analytics services apply current authorization and presentation policy. */
public interface AnalyticsExecutionReadModel {
    List<Map<String, Object>> statuses(Long tenant, Long actor, Collection<String> adoptionPids);
    List<Map<String, Object>> businessResults(Long tenant, Long actor, String adoptionPid, int limit, int offset);
}
