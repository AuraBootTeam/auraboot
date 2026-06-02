package com.auraboot.framework.plugin.extension;

/**
 * Declares one plugin REST route plus its governance metadata.
 *
 * <p>{@code pathPattern} is relative to {@code /api/ext/{namespace}} (the namespace
 * comes from the plugin manifest). {@code permissionCode} is REQUIRED for AUTHENTICATED
 * routes (enforced fail-closed at registry bind time in gamma-2); it must follow the
 * platform convention {@code <module>.<resource>.<action>}.
 */
public record RestRoute(
        String method,            // "GET" | "POST" | "PUT" | "DELETE"
        String pathPattern,       // e.g. "/whoami" or "/batches/{batchId}/records"
        String permissionCode,    // e.g. "probe.probe.read"
        AuthPolicy authPolicy,    // defaults to AUTHENTICATED when null
        boolean idempotent,       // honoured in gamma-2
        boolean readOnlyTx,       // honoured in gamma-2
        String requestJsonSchema  // optional; pre-validated in gamma-2; may be null
) {
    public RestRoute {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("RestRoute requires a non-blank method");
        }
        if (pathPattern == null || pathPattern.isBlank()) {
            throw new IllegalArgumentException("RestRoute requires a non-blank pathPattern");
        }
        if (authPolicy == null) {
            authPolicy = AuthPolicy.AUTHENTICATED;
        }
    }

    /** Convenience for a simple authenticated route. */
    public static RestRoute of(String method, String pathPattern, String permissionCode) {
        return new RestRoute(method, pathPattern, permissionCode, AuthPolicy.AUTHENTICATED, false, false, null);
    }
}
