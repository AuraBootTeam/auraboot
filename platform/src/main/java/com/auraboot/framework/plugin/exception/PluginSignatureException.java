package com.auraboot.framework.plugin.exception;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;

/**
 * Exception thrown when plugin package signature verification fails.
 *
 * <p>This is a hard failure — unsigned or tampered packages must not be installed
 * because plugins can execute arbitrary code on the server.
 */
public class PluginSignatureException extends RootUnCheckedException {

    private static final long serialVersionUID = 1L;

    private final String pluginId;

    public PluginSignatureException(String message) {
        super(ResponseCode.BUSINESS_ERROR, message);
        this.pluginId = null;
    }

    public PluginSignatureException(String message, String pluginId) {
        super(ResponseCode.BUSINESS_ERROR, message);
        this.pluginId = pluginId;
    }

    public PluginSignatureException(String message, Throwable cause) {
        super(ResponseCode.BUSINESS_ERROR, cause);
        this.pluginId = null;
    }

    public PluginSignatureException(String message, String pluginId, Throwable cause) {
        super(ResponseCode.BUSINESS_ERROR, cause);
        this.pluginId = pluginId;
    }

    public String getPluginId() {
        return pluginId;
    }

    @Override
    public String getMessage() {
        StringBuilder sb = new StringBuilder("Plugin signature verification failed");
        Object ctx = getContext();
        if (ctx != null) {
            sb.append(": ").append(ctx);
        }
        if (pluginId != null) {
            sb.append(" [pluginId=").append(pluginId).append("]");
        }
        return sb.toString();
    }
}
