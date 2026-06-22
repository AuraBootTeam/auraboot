package com.auraboot.framework.bpm.rule;

import com.auraboot.framework.bpm.entity.BpmRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowDemoLeaveValidationRuleTest {

    private final DroolsEngineService droolsEngineService = new DroolsEngineService(null);

    @Test
    void annualLeaveWithoutBalanceFailsClosed() throws IOException {
        Map<String, Object> result = droolsEngineService.evaluateRule(rule(), Map.of(
                "type", "annual",
                "days", 64,
                "attachmentCount", 0
        ));

        assertThat(result)
                .containsEntry("valid", false)
                .containsEntry("reason", "annual_leave_insufficient");
    }

    @Test
    void sickLeaveLongerThanTwoDaysWithoutAttachmentIsRejected() throws IOException {
        Map<String, Object> result = droolsEngineService.evaluateRule(rule(), Map.of(
                "type", "sick",
                "days", 3,
                "balanceRemaining", 10,
                "attachmentCount", 0
        ));

        assertThat(result)
                .containsEntry("valid", false)
                .containsEntry("reason", "sick_attachment_required");
    }

    private BpmRule rule() throws IOException {
        return BpmRule.builder()
                .pid("workflow-demo-validation-test")
                .ruleCode("wd_leave_validation")
                .version(1)
                .ruleContent(Files.readString(workflowDemoRulePath()))
                .build();
    }

    private Path workflowDemoRulePath() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path candidate : java.util.List.of(
                cwd.resolve("../plugins/workflow-demo/rules/wd_leave_validation.drl").normalize(),
                cwd.resolve("plugins/workflow-demo/rules/wd_leave_validation.drl").normalize())) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("Could not locate plugins/workflow-demo/rules/wd_leave_validation.drl from " + cwd);
    }
}
