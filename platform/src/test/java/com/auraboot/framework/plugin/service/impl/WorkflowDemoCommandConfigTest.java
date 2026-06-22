package com.auraboot.framework.plugin.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowDemoCommandConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void submitCommandsRunLeaveValidationBeforeStartingWorkflow() throws IOException {
        JsonNode commands = objectMapper.readTree(workflowDemoCommandsJson().toFile());

        assertLeaveValidationPreAction(
                findCommand(commands, "wd:create_and_submit_leave_request"),
                "payload");
        assertLeaveValidationPreAction(
                findCommand(commands, "wd:submit_leave_request"),
                "currentRecord");
    }

    private void assertLeaveValidationPreAction(JsonNode command, String sourceScope) {
        String code = command.path("code").asText();
        JsonNode preActions = command.path("preActions");

        assertThat(preActions.isArray())
                .as("%s must define preActions", code)
                .isTrue();
        assertThat(preActions).hasSize(1);

        JsonNode action = preActions.get(0);
        assertThat(action.path("type").asText()).isEqualTo("bpm:run-rule");
        assertThat(action.path("ruleCode").asText()).isEqualTo("wd_leave_validation");
        assertThat(action.at("/facts/type").asText()).isEqualTo("${" + sourceScope + ".wd_req_type}");
        assertThat(action.at("/facts/days").asText()).isEqualTo("${" + sourceScope + ".wd_req_days}");
        assertThat(action.at("/facts/balanceRemaining").asText())
                .isEqualTo("${balance.wd_bal_annual_remaining}");
        assertThat(action.at("/facts/attachmentCount").asText())
                .isEqualTo("${" + sourceScope + ".wd_req_attachments.size}");

        JsonNode lookup = action.path("contextLookup").get(0);
        assertThat(lookup.path("modelCode").asText()).isEqualTo("wd_leave_balance");
        assertThat(lookup.path("exposeAs").asText()).isEqualTo("balance");
        assertThat(lookup.at("/filters/0/field").asText()).isEqualTo("wd_bal_employee");
        assertThat(lookup.at("/filters/0/value").asText())
                .isEqualTo("${" + sourceScope + ".wd_req_applicant}");
    }

    private JsonNode findCommand(JsonNode commands, String code) {
        for (JsonNode command : commands) {
            if (code.equals(command.path("code").asText())) {
                return command;
            }
        }
        throw new AssertionError("workflow-demo command not found: " + code);
    }

    private Path workflowDemoCommandsJson() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate : List.of(
                cwd.resolve("../plugins/workflow-demo/config/commands.json").normalize(),
                cwd.resolve("plugins/workflow-demo/config/commands.json").normalize())) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("Could not locate plugins/workflow-demo/config/commands.json from " + cwd);
    }
}
