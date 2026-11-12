package com.auraboot.framework.meta.service.impl.pipeline;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandIdempotencyIntentTest {
    @Test void artifact_relationship_changes_alter_the_frozen_intent_without_changing_public_field_policy() {
        var binding = new HashMap<String,String>(Map.of("project","a".repeat(64)));
        var view = new com.auraboot.framework.meta.service.impl.CommandPublicOutputProjectionPlan(null,null,Map.of("root",
                new com.auraboot.framework.meta.service.impl.CommandPublicOutputProjectionPlan.ModelFields(
                        java.util.Set.of("file"),java.util.Set.of("file"),List.of(),List.of())));
        var context = CommandPipelineContext.builder().commandCode("root:export").publicOutputProjection(view).artifactRootBinding(binding).build();
        var before = CommandIdempotencyIntent.snapshot(context);
        binding.put("project","b".repeat(64));
        assertThat(before.get("artifactRootBinding")).isEqualTo(Map.of("project","a".repeat(64)));
        assertThat(CommandIdempotencyIntent.snapshot(context)).isNotEqualTo(before);
        context.setArtifactRootBinding(Map.of());
        assertThat(CommandIdempotencyIntent.snapshot(context)).doesNotContainKey("artifactRootBinding");
    }

    @Test
    void freezesEveryExecutionMeaningFieldBeforeLaterPhasesMutateThePayload() {
        Map<String, Object> nested = new HashMap<>(Map.of("part", "A"));
        List<Object> lines = new ArrayList<>(List.of(nested));
        Map<String, Object> payload = new HashMap<>(Map.of("lines", lines));
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setTargetRecordPid("REQ-1");
        request.setOperationType("UPDATE");
        request.setExpectedVersion(7);
        request.setDryRun(true);
        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode("dq:release")
                .request(request)
                .payload(payload)
                .build();

        Map<String, Object> frozen = CommandIdempotencyIntent.snapshot(ctx);
        nested.put("part", "B");
        lines.add("later-phase-value");
        payload.put("computed", 1);

        assertThat(frozen).containsEntry("commandCode", "dq:release")
                .containsEntry("targetRecordPid", "REQ-1")
                .containsEntry("operationType", "UPDATE")
                .containsEntry("expectedVersion", 7)
                .containsEntry("dryRun", true);
        assertThat(frozen.get("payload"))
                .isEqualTo(Map.of("lines", List.of(Map.of("part", "A"))));
        assertThatThrownBy(() -> frozen.put("payload", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsAnUnexpectedMutableObjectInsteadOfHashingAChangingReference() {
        CommandExecuteRequest request = new CommandExecuteRequest();
        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode("dq:release")
                .request(request)
                .payload(Map.of("unsafe", new StringBuilder("mutable")))
                .build();

        assertThatThrownBy(() -> CommandIdempotencyIntent.snapshot(ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported mutable command payload value");
    }

    @Test
    void bindsAReplayToTheAuthenticatedActor() {
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setTargetRecordPid("REQ-1");
        CommandPipelineContext firstCaller = CommandPipelineContext.builder()
                .commandCode("dq:release")
                .request(request)
                .userId(101L)
                .payload(Map.of("source", "same"))
                .build();
        CommandPipelineContext secondCaller = CommandPipelineContext.builder()
                .commandCode("dq:release")
                .request(request)
                .userId(202L)
                .payload(Map.of("source", "same"))
                .build();

        assertThat(CommandIdempotencyIntent.snapshot(firstCaller))
                .containsEntry("actorUserId", 101L)
                .isNotEqualTo(CommandIdempotencyIntent.snapshot(secondCaller));
    }
}
