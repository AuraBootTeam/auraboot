package com.auraboot.framework.plugin.extension;

import java.util.Map;

/**
 * Public platform port used by a workflow product to execute one platform-owned
 * automation action without depending on automation entities, mappers, or executors.
 */
public interface AutomationActionAccessor {
    String DELEGATE_BEAN_NAME = "automationActionServiceTaskDelegate";
    String ACTIONS_VAR = "_automation_actions";
    String ACTION_RESULTS_VAR = "_automation_action_results";
    String LOG_ID_VAR = "_automation_log_id";
    String AUTOMATION_ID_VAR = "_automation_id";
    String TENANT_ID_VAR = "_automation_tenant_id";
    String USER_ID_VAR = "_automation_user_id";
    String USER_PID_VAR = "_automation_user_pid";
    String USERNAME_VAR = "_automation_username";
    String MEMBER_ID_VAR = "_automation_member_id";

    /** Execute the action specification for {@code nodeId} in the supplied workflow variables. */
    void execute(String nodeId, String processInstanceId, Map<String, Object> processVariables);
}
