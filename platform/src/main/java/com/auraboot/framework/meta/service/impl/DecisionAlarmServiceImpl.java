package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.DecisionAlarm;
import com.auraboot.framework.meta.mapper.DecisionAlarmMapper;
import com.auraboot.framework.meta.service.DecisionAlarmService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Default {@link DecisionAlarmService}: direct mapper delegation, moved
 * verbatim from DecisionController/InvariantController so controllers stop
 * touching the mapper layer. No behavior change.
 */
@Service
@RequiredArgsConstructor
public class DecisionAlarmServiceImpl implements DecisionAlarmService {

    private final DecisionAlarmMapper alarmMapper;

    @Override
    public List<DecisionAlarm> findOpenAlarms(Long tenantId, int limit) {
        return alarmMapper.findOpenAlarms(tenantId, limit);
    }

    @Override
    public List<DecisionAlarm> findOpenAlarmsByType(Long tenantId, String alarmType, int limit) {
        return alarmMapper.findOpenAlarmsByType(tenantId, alarmType, limit);
    }

    @Override
    public void acknowledgeAlarm(Long id) {
        alarmMapper.acknowledgeAlarm(id);
    }

    @Override
    public void resolveAlarm(Long id) {
        alarmMapper.resolveAlarm(id);
    }
}
