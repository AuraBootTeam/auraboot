package com.auraboot.framework.bpm.rule;

import com.auraboot.framework.bpm.entity.BpmRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowDemoLeaveValidationRuleTest {

    private final DroolsEngineService droolsEngineService = new DroolsEngineService(null);

    @Test
    void annualLeaveWithoutBalanceRecordReportsBalanceNotFound() throws IOException {
        // contextLookup found no wd_leave_balance row for the applicant, so the platform
        // resolves balanceRemaining to null. That is "no balance on file", not "0 days left".
        Map<String, Object> facts = new HashMap<>();
        facts.put("type", "annual");
        facts.put("days", 1);
        facts.put("balanceRemaining", null);
        facts.put("attachmentCount", 0);

        Map<String, Object> result = droolsEngineService.evaluateRule(rule(), facts);

        assertThat(result)
                .containsEntry("valid", false)
                .containsEntry("reason", "annual_balance_not_found");
    }

    @Test
    void annualLeaveWithZeroRemainingIsInsufficientNotMissing() throws IOException {
        Map<String, Object> result = droolsEngineService.evaluateRule(rule(), Map.of(
                "type", "annual",
                "days", 1,
                "balanceRemaining", 0,
                "attachmentCount", 0
        ));

        assertThat(result)
                .containsEntry("valid", false)
                .containsEntry("reason", "annual_leave_insufficient");
    }

    @Test
    void annualLeaveExceedingRemainingBalanceIsRejected() throws IOException {
        Map<String, Object> result = droolsEngineService.evaluateRule(rule(), Map.of(
                "type", "annual",
                "days", 64,
                "balanceRemaining", 18,
                "attachmentCount", 0
        ));

        assertThat(result)
                .containsEntry("valid", false)
                .containsEntry("reason", "annual_leave_insufficient");
    }

    @Test
    void annualLeaveWithinRemainingBalanceIsAccepted() throws IOException {
        Map<String, Object> result = droolsEngineService.evaluateRule(rule(), Map.of(
                "type", "annual",
                "days", 4.5,
                "balanceRemaining", 18,
                "attachmentCount", 0
        ));

        assertThat(result).doesNotContainKey("reason");
        assertThat(result.get("valid")).isNotEqualTo(false);
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
