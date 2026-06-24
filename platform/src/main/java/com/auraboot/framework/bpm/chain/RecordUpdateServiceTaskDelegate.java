package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.delegation.JavaDelegation;
import com.auraboot.smart.framework.engine.model.assembly.IdBasedElement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Thin SmartEngine serviceTask delegate that updates a single field on a
 * dynamic model record.
 *
 * <p>Wired into BPMN via {@code smart:class="recordUpdateServiceTaskDelegate"}.
 * The node XML carries the following {@code smart:*} extension attributes:
 * <ul>
 *   <li>{@code smart:modelCode} — the model to update (required).</li>
 *   <li>{@code smart:recordPidVar} — name of the process variable that holds the
 *       record pid (default: {@code "recordPid"}).</li>
 *   <li>{@code smart:fieldName} — the field to update (required).</li>
 *   <li>{@code smart:fieldValue} — the literal string value to write (required).</li>
 * </ul>
 *
 * <p>Example usage: updating {@code wd_req_status} to {@code "approved"} after
 * a manager/HR approves a leave request via the approval user task.
 *
 * @since 7.3.1
 */
@Slf4j
@Component(BpmServiceTaskConstants.BEAN_RECORD_UPDATE_DELEGATE)
@RequiredArgsConstructor
public class RecordUpdateServiceTaskDelegate implements JavaDelegation {

    private final DynamicDataService dynamicDataService;

    @Override
    public void execute(ExecutionContext executionContext) {
        Map<String, Object> processVars = executionContext.getRequest();
        if (processVars == null) {
            processVars = new HashMap<>();
        }

        Map<String, String> properties = resolveProperties(executionContext);

        String modelCode = properties.get(BpmServiceTaskConstants.ATTR_MODEL_CODE);
        String recordPidVar = properties.getOrDefault(BpmServiceTaskConstants.ATTR_RECORD_PID_VAR, "recordPid");
        String fieldName = properties.get(BpmServiceTaskConstants.ATTR_FIELD_NAME);
        String fieldValue = properties.get(BpmServiceTaskConstants.ATTR_FIELD_VALUE);

        if (modelCode == null || modelCode.isBlank()) {
            throw new IllegalArgumentException("record-update-task missing 'modelCode'");
        }
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("record-update-task missing 'fieldName'");
        }
        if (fieldValue == null) {
            throw new IllegalArgumentException("record-update-task missing 'fieldValue'");
        }

        Object recordPidObj = processVars.get(recordPidVar);
        if (recordPidObj == null) {
            throw new IllegalStateException(
                    "record-update-task: process variable '" + recordPidVar + "' is null");
        }
        String recordPid = recordPidObj.toString();

        log.info("RecordUpdateDelegate: modelCode={}, recordPid={}, {}={}",
                modelCode, recordPid, fieldName, fieldValue);

        Map<String, Object> update = new HashMap<>();
        update.put(fieldName, fieldValue);
        dynamicDataService.update(modelCode, recordPid, update);

        log.info("RecordUpdateDelegate: updated {}.{} = '{}' for record {}",
                modelCode, fieldName, fieldValue, recordPid);
    }

    private Map<String, String> resolveProperties(ExecutionContext executionContext) {
        if (executionContext.getBaseElement() instanceof IdBasedElement idBased
                && idBased.getProperties() != null) {
            return idBased.getProperties();
        }
        return new HashMap<>();
    }
}
