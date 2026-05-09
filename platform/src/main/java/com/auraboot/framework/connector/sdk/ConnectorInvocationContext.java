package com.auraboot.framework.connector.sdk;

import java.util.Map;

/**
 * Per-call invocation context. Mirrors
 * {@code CommandHandlerExtension.CommandContext} so plugin authors can
 * recognise the shape.
 *
 * @param tenantId       owning tenant
 * @param connectorPid   target connector pid
 * @param endpointCode   target endpoint code
 * @param params         caller-supplied params (must not be null; use {@link Map#of()})
 * @param dryRun         when true, side-effect-bearing endpoints must short-circuit
 * @since 5.2.0
 */
public record ConnectorInvocationContext(
        Long tenantId,
        String connectorPid,
        String endpointCode,
        Map<String, Object> params,
        boolean dryRun
) {
}
