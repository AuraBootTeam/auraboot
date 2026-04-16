package com.auraboot.framework.bpm.engine.adapter;

import com.auraboot.framework.bpm.audit.BpmAuditOperation;
import com.auraboot.framework.bpm.engine.BpmEngine;
import com.auraboot.framework.bpm.engine.dto.HistoryRecord;
import com.auraboot.framework.bpm.engine.dto.ProcessInstanceInfo;
import com.auraboot.framework.bpm.engine.dto.ProcessInstanceInfo.ProcessStatus;
import com.auraboot.framework.bpm.engine.dto.TaskInfo;
import com.auraboot.framework.bpm.engine.exception.BpmEngineException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BpmEngine adapter that wraps Alibaba SmartEngine.
 * <p>
 * This is the default (and currently only) production adapter.
 * When SmartEngine is on the classpath the auto-configuration will
 * register this bean; otherwise it is skipped via a
 * {@code @ConditionalOnClass} guard.
 * <p>
 * <b>Implementation note:</b> SmartEngine does not expose a public Maven
 * artefact at this time, so this adapter uses an in-memory stub that
 * faithfully implements the {@link BpmEngine} contract. Once SmartEngine
 * is available on the classpath, the stub sections should be replaced
 * with real SmartEngine API calls (marked with {@code // TODO: replace}).
 */
public class SmartEngineBpmAdapter implements BpmEngine {

    private static final Logger log = LoggerFactory.getLogger(SmartEngineBpmAdapter.class);
    private static final String ENGINE_TYPE = "smartengine";

    // ── In-memory state (replace with SmartEngine ProcessEngineConfiguration) ──

    /** Deployed definitions: processKey -> bpmnXml */
    private final Map<String, String> deployedDefinitions = new ConcurrentHashMap<>();

    /** Running instances: processInstanceId -> info */
    private final Map<String, ProcessInstanceInfo> instances = new ConcurrentHashMap<>();

    /** Active tasks: taskId -> TaskInfo */
    private final Map<String, TaskInfo> tasks = new ConcurrentHashMap<>();

    /** History log: processInstanceId -> records */
    private final Map<String, List<HistoryRecord>> historyLog = new ConcurrentHashMap<>();

    // ── Process lifecycle ──────────────────────────────────────────────

    @Override
    public void deployProcess(String processKey, String bpmnXml) {
        Objects.requireNonNull(processKey, "processKey must not be null");
        Objects.requireNonNull(bpmnXml, "bpmnXml must not be null");
        // TODO: replace with SmartEngine ProcessEngineConfiguration.deploy()
        deployedDefinitions.put(processKey, bpmnXml);
        log.info("[SmartEngine] Deployed process definition: {}", processKey);
    }

    @Override
    public ProcessInstanceInfo startProcess(String processKey, String businessKey,
                                            Map<String, Object> variables) {
        if (!deployedDefinitions.containsKey(processKey)) {
            throw new BpmEngineException(ENGINE_TYPE,
                    "Process definition not found: " + processKey);
        }

        // TODO: replace with SmartEngine smartEngine.start(processKey, variables)
        String instanceId = UUID.randomUUID().toString();
        ProcessInstanceInfo info = ProcessInstanceInfo.builder()
                .processInstanceId(instanceId)
                .processDefinitionKey(processKey)
                .businessKey(businessKey)
                .status(ProcessStatus.RUNNING)
                .variables(variables != null ? new HashMap<>(variables) : new HashMap<>())
                .startTime(LocalDateTime.now())
                .build();

        instances.put(instanceId, info);

        // Create an initial user task so getActiveTasks is meaningful
        String taskId = UUID.randomUUID().toString();
        TaskInfo initialTask = TaskInfo.builder()
                .taskId(taskId)
                .taskName("Initial Review")
                .taskDefinitionKey("initial_review")
                .processInstanceId(instanceId)
                .createTime(LocalDateTime.now())
                .variables(variables != null ? new HashMap<>(variables) : new HashMap<>())
                .build();
        tasks.put(taskId, initialTask);

        appendHistory(instanceId, "startEvent", "Process Started", "startEvent", null);

        log.info("[SmartEngine] Started process instance {} for key {}", instanceId, processKey);
        return info;
    }

    @Override
    public ProcessInstanceInfo getProcessInstance(String processInstanceId) {
        ProcessInstanceInfo info = instances.get(processInstanceId);
        if (info == null) {
            throw new BpmEngineException(ENGINE_TYPE,
                    "Process instance not found: " + processInstanceId);
        }
        return info;
    }

    @Override
    public void suspendProcess(String processInstanceId) {
        ProcessInstanceInfo info = requireRunning(processInstanceId);
        // TODO: replace with SmartEngine suspend API
        info.setStatus(ProcessStatus.SUSPENDED);
        appendHistory(processInstanceId, "suspend", "Process Suspended", "intermediateEvent", null);
        log.info("[SmartEngine] Suspended process instance {}", processInstanceId);
    }

    @Override
    public void resumeProcess(String processInstanceId) {
        ProcessInstanceInfo info = instances.get(processInstanceId);
        if (info == null || info.getStatus() != ProcessStatus.SUSPENDED) {
            throw new BpmEngineException(ENGINE_TYPE,
                    "Process instance is not suspended: " + processInstanceId);
        }
        // TODO: replace with SmartEngine resume API
        info.setStatus(ProcessStatus.RUNNING);
        appendHistory(processInstanceId, "resume", "Process Resumed", "intermediateEvent", null);
        log.info("[SmartEngine] Resumed process instance {}", processInstanceId);
    }

    @Override
    public void cancelProcess(String processInstanceId) {
        ProcessInstanceInfo info = instances.get(processInstanceId);
        if (info == null) {
            throw new BpmEngineException(ENGINE_TYPE,
                    "Process instance not found: " + processInstanceId);
        }
        if (info.getStatus() == ProcessStatus.COMPLETED || info.getStatus() == ProcessStatus.CANCELLED) {
            throw new BpmEngineException(ENGINE_TYPE,
                    "Process instance already terminated: " + processInstanceId);
        }
        // TODO: replace with SmartEngine cancel/abort API
        info.setStatus(ProcessStatus.CANCELLED);
        info.setEndTime(LocalDateTime.now());
        // Remove active tasks
        tasks.values().removeIf(t -> t.getProcessInstanceId().equals(processInstanceId));
        appendHistory(processInstanceId, "cancel", "Process Cancelled", "endEvent", null);
        log.info("[SmartEngine] Cancelled process instance {}", processInstanceId);
    }

    // ── Task operations ────────────────────────────────────────────────

    @Override
    public void completeTask(String taskId, Map<String, Object> variables) {
        TaskInfo task = tasks.get(taskId);
        if (task == null) {
            throw new BpmEngineException(ENGINE_TYPE, "Task not found: " + taskId);
        }
        // TODO: replace with SmartEngine taskService.complete(taskId, variables)
        String pid = task.getProcessInstanceId();
        tasks.remove(taskId);

        appendHistory(pid, task.getTaskDefinitionKey(), task.getTaskName(),
                "userTask", task.getAssignee());

        // If no more tasks remain for this instance, mark it completed
        boolean hasMoreTasks = tasks.values().stream()
                .anyMatch(t -> t.getProcessInstanceId().equals(pid));
        if (!hasMoreTasks) {
            ProcessInstanceInfo info = instances.get(pid);
            if (info != null && info.getStatus() == ProcessStatus.RUNNING) {
                info.setStatus(ProcessStatus.COMPLETED);
                info.setEndTime(LocalDateTime.now());
                appendHistory(pid, "endEvent", "Process Completed", "endEvent", null);
            }
        }
        log.info("[SmartEngine] Completed task {} in process {}", taskId, pid);
    }

    @Override
    public List<TaskInfo> getActiveTasks(String processInstanceId) {
        // TODO: replace with SmartEngine taskService query
        return tasks.values().stream()
                .filter(t -> t.getProcessInstanceId().equals(processInstanceId))
                .toList();
    }

    @Override
    public List<TaskInfo> getTasksByAssignee(String assignee) {
        // TODO: replace with SmartEngine taskService query by assignee
        return tasks.values().stream()
                .filter(t -> assignee.equals(t.getAssignee()))
                .toList();
    }

    // ── History ────────────────────────────────────────────────────────

    @Override
    public List<HistoryRecord> getProcessHistory(String processInstanceId) {
        return historyLog.getOrDefault(processInstanceId, List.of());
    }

    // ── Metadata ───────────────────────────────────────────────────────

    @Override
    public String getEngineType() {
        return ENGINE_TYPE;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private ProcessInstanceInfo requireRunning(String processInstanceId) {
        ProcessInstanceInfo info = instances.get(processInstanceId);
        if (info == null || info.getStatus() != ProcessStatus.RUNNING) {
            throw new BpmEngineException(ENGINE_TYPE,
                    "Process instance is not running: " + processInstanceId);
        }
        return info;
    }

    private void appendHistory(String processInstanceId, String activityId,
                               String activityName, String activityType,
                               String executedBy) {
        HistoryRecord record = HistoryRecord.builder()
                .id(UUID.randomUUID().toString())
                .processInstanceId(processInstanceId)
                .activityId(activityId)
                .activityName(activityName)
                .activityType(activityType)
                .executedBy(executedBy)
                .startTime(LocalDateTime.now())
                .endTime(LocalDateTime.now())
                .durationMillis(0L)
                .build();
        historyLog.computeIfAbsent(processInstanceId, k -> new ArrayList<>()).add(record);
    }
}
