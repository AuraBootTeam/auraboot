package com.auraboot.framework.bpm.config;

import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.configuration.MultiInstanceCounter;
import com.auraboot.smart.framework.engine.constant.AdHocConstant;
import com.auraboot.smart.framework.engine.service.param.query.TaskInstanceQueryParam;
import com.auraboot.smart.framework.engine.service.query.TaskQueryService;

import java.util.Collections;

/**
 * Default multi-instance counter used by SmartEngine's {@code UserTaskBehavior} when a
 * userTask carries {@code multiInstanceLoopCharacteristics}.
 *
 * <p>SmartEngine needs this SPI to evaluate {@code completionCondition} expressions at
 * task-complete time — it counts TaskInstance rows for a given (processInstanceId,
 * activityInstanceId) bucket filtered by the task tag. The MVEL-style invariant
 * {@code nrOfCompletedInstances + nrOfRejectedInstance <= nrOfInstances} depends on
 * this counter returning accurate passed/rejected totals.
 *
 * <p>Contract:
 * <ul>
 *   <li>Passed = TaskInstances tagged {@link AdHocConstant#AGREE}.
 *   <li>Rejected = TaskInstances tagged {@link AdHocConstant#DISAGREE}.
 * </ul>
 *
 * <p>Callers must include {@code RequestMapSpecialKeyConstant.TASK_INSTANCE_TAG} in the
 * variables map when completing multi-instance tasks; AuraBoot's {@code TaskService}
 * defaults this to {@code agree} so the normal "approve" flow works without extra wiring.
 *
 * @see com.auraboot.smart.framework.engine.behavior.impl.UserTaskBehavior#handleMultiInstance
 */
public class DefaultMultiInstanceCounter implements MultiInstanceCounter {

    @Override
    public Integer countPassedTaskInstanceNumber(String processInstanceId,
                                                 String activityInstanceId,
                                                 SmartEngine smartEngine) {
        return count(processInstanceId, activityInstanceId, AdHocConstant.AGREE, smartEngine);
    }

    @Override
    public Integer countRejectedTaskInstanceNumber(String processInstanceId,
                                                   String activityInstanceId,
                                                   SmartEngine smartEngine) {
        return count(processInstanceId, activityInstanceId, AdHocConstant.DISAGREE, smartEngine);
    }

    private Integer count(String processInstanceId, String activityInstanceId,
                          String tag, SmartEngine smartEngine) {
        TaskQueryService taskQueryService = smartEngine.getTaskQueryService();
        TaskInstanceQueryParam param = new TaskInstanceQueryParam();
        param.setProcessInstanceIdList(Collections.singletonList(processInstanceId));
        param.setActivityInstanceId(activityInstanceId);
        param.setTag(tag);
        return taskQueryService.count(param).intValue();
    }
}
