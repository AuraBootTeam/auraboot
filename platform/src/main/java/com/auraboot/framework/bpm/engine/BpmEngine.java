package com.auraboot.framework.bpm.engine;

import com.auraboot.framework.bpm.engine.dto.HistoryRecord;
import com.auraboot.framework.bpm.engine.dto.ProcessInstanceInfo;
import com.auraboot.framework.bpm.engine.dto.TaskInfo;
import com.auraboot.framework.bpm.engine.exception.BpmEngineException;

import java.util.List;
import java.util.Map;

/**
 * Engine-agnostic BPM abstraction layer.
 * <p>
 * All business code should depend on this interface instead of any specific
 * engine (SmartEngine, Camunda, Flowable, etc.). Concrete implementations
 * are provided as adapters in the {@code adapter} sub-package.
 */
public interface BpmEngine {

    // ── Process lifecycle ──────────────────────────────────────────────

    /**
     * Deploy a process definition from a BPMN 2.0 XML resource.
     *
     * @param processKey   logical key / name of the process definition
     * @param bpmnXml      BPMN 2.0 XML content
     * @throws BpmEngineException if deployment fails
     */
    void deployProcess(String processKey, String bpmnXml);

    /**
     * Start a new process instance.
     *
     * @param processKey  the process definition key
     * @param businessKey optional business correlation key (may be {@code null})
     * @param variables   initial process variables
     * @return information about the newly created instance
     */
    ProcessInstanceInfo startProcess(String processKey, String businessKey, Map<String, Object> variables);

    /**
     * Retrieve the current state of a process instance.
     */
    ProcessInstanceInfo getProcessInstance(String processInstanceId);

    /**
     * Suspend a running process instance (no tasks can be completed while suspended).
     */
    void suspendProcess(String processInstanceId);

    /**
     * Resume a previously suspended process instance.
     */
    void resumeProcess(String processInstanceId);

    /**
     * Cancel / terminate a process instance.
     */
    void cancelProcess(String processInstanceId);

    // ── Task operations ────────────────────────────────────────────────

    /**
     * Complete a user task, optionally passing output variables.
     */
    void completeTask(String taskId, Map<String, Object> variables);

    /**
     * Return all active (uncompleted) tasks for a process instance.
     */
    List<TaskInfo> getActiveTasks(String processInstanceId);

    /**
     * Return all active tasks assigned to or claimable by a given user.
     */
    List<TaskInfo> getTasksByAssignee(String assignee);

    // ── History ────────────────────────────────────────────────────────

    /**
     * Return the full execution history for a process instance.
     */
    List<HistoryRecord> getProcessHistory(String processInstanceId);

    // ── Metadata ───────────────────────────────────────────────────────

    /**
     * Return the engine type identifier (e.g. "smartengine", "camunda", "flowable").
     */
    String getEngineType();
}
