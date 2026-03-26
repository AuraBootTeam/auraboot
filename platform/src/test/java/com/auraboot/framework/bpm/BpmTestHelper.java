package com.auraboot.framework.bpm;

import com.auraboot.framework.bpm.entity.BpmNodeHook;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.mapper.BpmNodeHookMapper;
import com.auraboot.framework.bpm.mapper.SlaConfigMapper;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.common.util.UlidGenerator;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Shared test helper for BPM integration tests.
 * Provides BPMN templates and convenience methods for process creation.
 */
public final class BpmTestHelper {

    private BpmTestHelper() {}

    // ==================== BPMN Templates ====================

    public static final String SIMPLE_APPROVAL_BPMN_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:smart="http://smart.alibaba.com"
                         targetNamespace="http://auraboot.com/bpm">
                <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <userTask id="approval" name="Approval"
                              smart:assigneeType="user"
                              smart:assigneeId="%s"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="approval"/>
                    <sequenceFlow id="f2" sourceRef="approval" targetRef="end"/>
                </process>
            </definitions>
            """;

    public static final String MULTI_TASK_BPMN_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:smart="http://smart.alibaba.com"
                         targetNamespace="http://auraboot.com/bpm">
                <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <userTask id="task1" name="Task 1"
                              smart:assigneeType="user"
                              smart:assigneeId="testuser1"/>
                    <userTask id="task2" name="Task 2"
                              smart:assigneeType="user"
                              smart:assigneeId="testuser1"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="task1"/>
                    <sequenceFlow id="f2" sourceRef="task1" targetRef="task2"/>
                    <sequenceFlow id="f3" sourceRef="task2" targetRef="end"/>
                </process>
            </definitions>
            """;

    public static final String SERVICE_TASK_BPMN_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:smart="http://smart.alibaba.com"
                         targetNamespace="http://auraboot.com/bpm">
                <process id="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <serviceTask id="serviceTask1" name="Service Task"/>
                    <endEvent id="end"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="serviceTask1"/>
                    <sequenceFlow id="f2" sourceRef="serviceTask1" targetRef="end"/>
                </process>
            </definitions>
            """;

    // ==================== Process Creation Helpers ====================

    /**
     * Create and deploy a simple approval process.
     *
     * @return the process key of the deployed process
     */
    public static String createAndDeploy(ProcessDeploymentService deploymentService,
                                          String suffix, String assigneeId) {
        String processKey = "test-" + suffix + "-" + System.nanoTime();
        String bpmn = String.format(SIMPLE_APPROVAL_BPMN_TEMPLATE, processKey, assigneeId);

        ProcessDeploymentService.CreateProcessRequest request =
                new ProcessDeploymentService.CreateProcessRequest(
                        processKey, "Test Process " + suffix,
                        "Integration test process", "test", bpmn,
                        null, null, null);

        var definition = deploymentService.create(request);
        deploymentService.deploy(definition.getPid());
        return processKey;
    }

    /**
     * Create, deploy, and start a simple approval process.
     */
    public static String createDeployAndStart(ProcessDeploymentService deploymentService,
                                               ProcessEngineService engineService,
                                               String suffix) {
        String processKey = createAndDeploy(deploymentService, suffix, "system");
        var instance = engineService.startProcess(processKey, "biz-" + suffix, Map.of());
        return instance.getInstanceId();
    }

    // ==================== SLA Helpers ====================

    /**
     * Create an SLA config and insert it into DB.
     */
    public static SlaConfigEntity createSlaConfig(SlaConfigMapper mapper, Long tenantId,
                                                    String targetKey, String deadlineValue,
                                                    List<Map<String, Object>> warningRules) {
        SlaConfigEntity config = SlaConfigEntity.builder()
                .pid(UlidGenerator.generate())
                .tenantId(tenantId)
                .name("Test SLA - " + targetKey)
                .targetType("process")
                .targetKey(targetKey)
                .deadlineMode("fixed")
                .deadlineValue(deadlineValue)
                .warningRules(warningRules)
                .suspendPolicy("pause")
                .enabled(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        mapper.insert(config);
        return config;
    }

    // ==================== Hook Helpers ====================

    /**
     * Create a node hook and insert it into DB.
     */
    public static BpmNodeHook createNodeHook(BpmNodeHookMapper mapper, Long tenantId,
                                              String processKey, String nodeId,
                                              String hookType, Map<String, Object> config,
                                              String failStrategy) {
        BpmNodeHook hook = BpmNodeHook.builder()
                .pid(UlidGenerator.generate())
                .tenantId(tenantId)
                .processKey(processKey)
                .nodeId(nodeId)
                .hookType(hookType)
                .executionOrder(0)
                .hookConfig(config)
                .failStrategy(failStrategy)
                .async(false)
                .enabled(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        mapper.insert(hook);
        return hook;
    }
}
