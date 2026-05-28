package com.auraboot.framework.automation.iot;

import java.util.Map;

/**
 * Pluggable sink for outcomes emitted by {@link IotActionNode}.
 *
 * <p>In production this fans out to Kafka topics ({@code iot.alarm.v1},
 * {@code iot.cmd.req.v1}), to the AuraBoot command bus, or to a BPM workflow
 * start. For the spike + unit tests an in-memory {@code RecordingIotActionSink}
 * is wired so we can assert on the envelopes without standing up Kafka.
 *
 * <p>Multiple implementations may coexist; {@link IotActionNode} fans each
 * outcome out to every {@code IotActionSink} bean in the context.
 */
public interface IotActionSink {

    /**
     * @param kind       outcome kind — one of {@code "alarm"}, {@code "command"},
     *                   {@code "record"}, {@code "workflow"} (extensible)
     * @param envelope   serialized outcome payload (Jackson-friendly map)
     */
    void emit(String kind, Map<String, Object> envelope);
}
