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
}
