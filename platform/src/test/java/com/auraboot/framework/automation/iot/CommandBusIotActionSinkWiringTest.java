package com.auraboot.framework.automation.iot;

import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.CommandExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Closes the "sink → live command bus" hop of G3 (digital-thread IoT bridge):
 * an {@code iot_action} rule node, when run, must fan its outcome out to the
 * production {@link CommandBusIotActionSink} and have that sink route a
 * {@code kind=command} envelope onto the AuraBoot command bus
 * ({@link CommandExecutor}).
 *
 * <p>Both real classes are wired together (only {@link CommandExecutor} — the
 * boundary to the command pipeline, separately proven full-stack against
 * {@code pe:capture_iot_reading} — is mocked). At runtime Spring injects every
 * {@code IotActionSink} bean into {@link IotActionNode}'s constructor list, so
 * the {@code @Component} {@code CommandBusIotActionSink} is present exactly as
 * exercised here.
 */
class CommandBusIotActionSinkWiringTest {

    private static AutomationAction action(Map<String, Object> cfg) {
        return AutomationAction.builder().type(IotActionNode.TYPE).config(cfg).build();
    }

    @Test
    @DisplayName("iot_action kind=command → CommandBusIotActionSink routes to the command bus")
    void iotActionNode_routesCommandToCommandBus() {
        CommandExecutor commandExecutor = mock(CommandExecutor.class);
        CommandBusIotActionSink sink = new CommandBusIotActionSink(commandExecutor);
        IotActionNode node = new IotActionNode(List.of(sink));

        Map<String, Object> ctx = new HashMap<>();
        ctx.put(IotRuleContextKeys.TENANT_ID, 1L);
        ctx.put(IotRuleContextKeys.DEVICE_ID, "dev-1");

        Map<String, Object> payload = new HashMap<>();
        payload.put("command", "pe:capture_iot_reading");
        payload.put("dataPointId", "dp-1");
        payload.put("value", 261.3);
        payload.put("isAlarm", true);

        Object result = node.execute(action(Map.of("kind", "command", "payload", payload)), ctx);

        // the node reports it emitted to the one wired sink
        assertThat(result).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("emitted", true).containsEntry("sinks", 1);

        // the sink stripped the routing key and forwarded the rest onto the command bus
        ArgumentCaptor<CommandExecuteRequest> req = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        verify(commandExecutor).execute(eq("pe:capture_iot_reading"), req.capture());
        Map<String, Object> forwarded = req.getValue().getPayload();
        assertThat(forwarded).doesNotContainKey("command");
        assertThat(forwarded).containsEntry("dataPointId", "dp-1")
                .containsEntry("value", 261.3).containsEntry("isAlarm", true);
        assertThat(req.getValue().getClientRequestId()).contains("dev-1");
    }

    @Test
    @DisplayName("iot_action kind=alarm → CommandBusIotActionSink does NOT touch the command bus")
    void iotActionNode_alarmKind_doesNotInvokeCommandBus() {
        CommandExecutor commandExecutor = mock(CommandExecutor.class);
        CommandBusIotActionSink sink = new CommandBusIotActionSink(commandExecutor);
        IotActionNode node = new IotActionNode(List.of(sink));

        Map<String, Object> ctx = new HashMap<>(Map.of(IotRuleContextKeys.TENANT_ID, 1L));
        Map<String, Object> payload = new HashMap<>();
        payload.put("metric", "temperature");
        payload.put("value", 95.0);

        node.execute(action(Map.of("kind", "alarm", "payload", payload)), ctx);

        verify(commandExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("dropped-upstream run does not emit (no command routed)")
    void droppedRun_doesNotRoute() {
        CommandExecutor commandExecutor = mock(CommandExecutor.class);
        IotActionNode node = new IotActionNode(List.of(new CommandBusIotActionSink(commandExecutor)));

        Map<String, Object> ctx = new HashMap<>();
        ctx.put(IotRuleContextKeys.DROPPED, Boolean.TRUE);
        Map<String, Object> payload = new HashMap<>(Map.of("command", "pe:capture_iot_reading"));

        node.execute(action(Map.of("kind", "command", "payload", payload)), ctx);

        verify(commandExecutor, never()).execute(any(), any());
    }
}
