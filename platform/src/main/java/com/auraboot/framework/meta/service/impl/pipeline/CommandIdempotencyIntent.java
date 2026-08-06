package com.auraboot.framework.meta.service.impl.pipeline;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;

import java.lang.reflect.Array;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the immutable, canonical command intent fenced by an idempotency key.
 *
 * <p>The authenticated actor and command payload are intentionally captured before any
 * normalize/auto-set/computed phase can mutate state. Claim and completion then hash the exact same
 * object, so successful execution can never write an outcome under a different actor or intent than
 * the one that entered the handler.</p>
 */
public final class CommandIdempotencyIntent {

    private CommandIdempotencyIntent() {
        // utility class
    }

    public static Map<String, Object> snapshot(CommandPipelineContext ctx) {
        CommandExecuteRequest request = ctx.getRequest();
        Map<String, Object> intent = new LinkedHashMap<>();
        // A cached command response may contain actor-specific data. Binding the immutable intent
        // to the authenticated user makes a known key unusable by another caller: the durable
        // idempotency claim sees an intent mismatch and fails closed instead of replaying it.
        intent.put("actorUserId", ctx.getUserId());
        intent.put("commandCode", ctx.getCommandCode());
        intent.put("targetRecordPid", request == null ? null : request.getTargetRecordId());
        intent.put("operationType", request == null ? null : request.getOperationType());
        intent.put("expectedVersion", request == null ? null : request.getExpectedVersion());
        intent.put("payload", immutableValue(ctx.getPayload()));
        intent.put("dryRun", request != null && request.isDryRun());
        return Collections.unmodifiableMap(intent);
    }

    private static Object immutableValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>
                || value instanceof TemporalAccessor) {
            return value;
        }
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nestedValue) -> {
                if (!(key instanceof String textKey)) {
                    throw new IllegalArgumentException(
                            "Command idempotency payload maps require string keys");
                }
                copy.put(textKey, immutableValue(nestedValue));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) {
            return immutableList(collection);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            ArrayList<Object> copy = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                copy.add(immutableValue(Array.get(value, i)));
            }
            return Collections.unmodifiableList(copy);
        }

        // Command requests are JSON-shaped. Reject an unexpected mutable object instead of
        // retaining a reference that could change the request hash between claim and completion.
        throw new IllegalArgumentException(
                "Unsupported mutable command payload value: " + value.getClass().getName());
    }

    private static Object immutableList(Collection<?> collection) {
        ArrayList<Object> copy = new ArrayList<>(collection.size());
        for (Object value : collection) {
            copy.add(immutableValue(value));
        }
        return Collections.unmodifiableList(copy);
    }
}
