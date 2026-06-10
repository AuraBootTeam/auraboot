package com.auraboot.framework.automation.iot;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.CommandExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * IotActionSink that bridges IoT rule outcomes of kind {@code "command"} or {@code "record"}
 * to the AuraBoot command bus via {@link CommandExecutor}.
 *
 * <p>Envelope shape expected from {@link IotActionNode}:
 * <pre>{@code
 * {
 *   "kind": "command",
 *   "deviceId": "dev-001",
 *   "tenantId": 1,
 *   "emittedAt": 1718000000000,
 *   "payload": {
 *     "command": "pe:capture_iot_reading",
 *     "dataPointId": "dp-uuid",
 *     "value": 98.5,
 *     "isAlarm": false
 *   }
 * }
 * }</pre>
 *
 * <p>The {@code payload.command} key selects the command to execute; the remainder of the
 * payload (minus {@code "command"}) is forwarded as the command's payload. Tenant context is
 * set on the calling thread and cleared in a try/finally.
 *
 * <p>Kinds {@code "alarm"} and {@code "workflow"} are out of this slice (those go to Kafka
 * topics) — they are silently ignored here. No catch-swallow: failures surface to the caller.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandBusIotActionSink implements IotActionSink {

    private final CommandExecutor commandExecutor;

    @Override
    @SuppressWarnings("unchecked")
    public void emit(String kind, Map<String, Object> envelope) {
        if (!"command".equals(kind) && !"record".equals(kind)) {
            // alarm / workflow are handled by Kafka sinks (out of this slice)
            return;
        }

        Object payloadObj = envelope.get("payload");
        if (!(payloadObj instanceof Map)) {
            log.warn("CommandBusIotActionSink: envelope missing 'payload' map for kind={}, deviceId={}",
                    kind, envelope.get("deviceId"));
            return;
        }
        Map<String, Object> rawPayload = (Map<String, Object>) payloadObj;

        String command = (String) rawPayload.get("command");
        if (!StringUtils.hasText(command)) {
            log.warn("CommandBusIotActionSink: payload.command is blank for kind={}, deviceId={}",
                    kind, envelope.get("deviceId"));
            return;
        }

        // Build payload without the routing key "command"
        Map<String, Object> payloadMinusCommand = new HashMap<>(rawPayload);
        payloadMinusCommand.remove("command");

        // Build clientRequestId for idempotency
        Object deviceId = envelope.get("deviceId");
        Object emittedAt = envelope.get("emittedAt");
        String clientRequestId = "iot-" + deviceId + "-" + emittedAt;

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(payloadMinusCommand);
        req.setClientRequestId(clientRequestId);

        // Set tenant context for this programmatic call; clear in finally
        Object tenantIdObj = envelope.get("tenantId");
        boolean tenantSet = false;
        if (tenantIdObj instanceof Number) {
            MetaContext.setCurrentTenantId(((Number) tenantIdObj).longValue());
            tenantSet = true;
        }

        try {
            commandExecutor.execute(command, req);
            log.info("CommandBusIotActionSink: executed command={} deviceId={} emittedAt={}",
                    command, deviceId, emittedAt);
        } finally {
            if (tenantSet) {
                MetaContext.clear();
            }
        }
    }
}
