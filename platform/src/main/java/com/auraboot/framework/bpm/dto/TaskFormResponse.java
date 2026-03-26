package com.auraboot.framework.bpm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
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
}
