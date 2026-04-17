package com.auraboot.framework.bpm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskFormResponse {
    private String taskId;
    private String taskName;
    private String processName;
    private String processInstanceId;
    private String nodeId;
    private FormBindingConfig formBinding;
    private String businessKey;
    private Map<String, Object> processVariables;

    /**
     * Task actions declared on the node's designerJson {@code data.taskActions}.
     * {@code null} when the process definition was authored without designerJson
     * (pure BPMN XML) or when the node has no taskActions entry. The frontend
     * uses this to forward {@code resultVariable}/{@code resultValue} as process
     * variables when the user clicks approve/reject, so downstream exclusive
     * gateway conditions resolve.
     */
    private List<TaskActionDef> taskActions;
}
