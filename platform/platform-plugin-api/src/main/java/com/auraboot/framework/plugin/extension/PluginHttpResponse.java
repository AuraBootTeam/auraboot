package com.auraboot.framework.plugin.extension;

import java.io.OutputStream;

/**
 * Platform-neutral response writer handed to a {@link RestEndpointExtension}.
 * Supports JSON (write UTF-8 bytes) and binary/streaming (write raw bytes) responses.
 */
public interface PluginHttpResponse {

    /** Set the HTTP status code. Returns this for chaining. */
    PluginHttpResponse status(int code);

    /** Add a response header. Returns this for chaining. */
    PluginHttpResponse header(String name, String value);

    /** Set the Content-Type. Returns this for chaining. */
    PluginHttpResponse contentType(String mediaType);

    /** Body output stream. The platform flushes/closes it after handle() returns. */
    OutputStream out();
}
