package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.entity.DecisionAlarm;

import java.util.List;

/**
 * Read/transition access to decision alarms for controllers (ArchUnit rule 2:
 * controllers must not touch the mapper layer). Covers decision-console alarms
 * and the invariant-violation alarm variant.
 */
public interface DecisionAlarmService {

    List<DecisionAlarm> findOpenAlarms(Long tenantId, int limit);

    List<DecisionAlarm> findOpenAlarmsByType(Long tenantId, String alarmType, int limit);

    void acknowledgeAlarm(Long id);

    void resolveAlarm(Long id);
}
