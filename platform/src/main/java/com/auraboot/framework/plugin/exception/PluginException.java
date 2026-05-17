package com.auraboot.framework.plugin.exception;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;

/**
 * Base exception for plugin-related errors.
 */
public class PluginException extends RootUnCheckedException {

    private static final long serialVersionUID = 1L;

    private final String pluginId;
    private final String namespace;

    public PluginException(String message) {
        super(ResponseCode.BUSINESS_ERROR, message);
        this.pluginId = null;
        this.namespace = null;
    }

    public PluginException(String message, String pluginId, String namespace) {
        super(ResponseCode.BUSINESS_ERROR, message);
        this.pluginId = pluginId;
        this.namespace = namespace;
    }

    public PluginException(String message, Throwable cause) {
        super(ResponseCode.BUSINESS_ERROR, message, cause);
        this.pluginId = null;
        this.namespace = null;
    }

    public PluginException(String message, String pluginId, String namespace, Throwable cause) {
        super(ResponseCode.BUSINESS_ERROR, message, cause);
        this.pluginId = pluginId;
        this.namespace = namespace;
    }

    public String getPluginId() {
        return pluginId;
    }

    public String getNamespace() {
        return namespace;
    }

    @Override
    public String getMessage() {
        StringBuilder sb = new StringBuilder();
        Object ctx = getContext();
        if (ctx != null) {
            sb.append(ctx.toString());
        }
        if (pluginId != null) {
            sb.append(" [pluginId=").append(pluginId);
            if (namespace != null) {
                sb.append(", namespace=").append(namespace);
            }
            sb.append("]");
        }
        return sb.toString();
    }
}
