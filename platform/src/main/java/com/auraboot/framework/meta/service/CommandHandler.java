package com.auraboot.framework.meta.service;

import java.util.Map;

/**
 * Command Handler SPI interface.
 * Implement this interface and register as Spring bean for HANDLER phase extension.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
public interface CommandHandler {

    /**
     * Handler name, used to match BindingRule.handlerClass
     */
    String getHandlerName();

    /**
     * Execute handler logic
     *
     * @param context execution context with payload, command info, and intermediate results
     * @return handler output data (merged into command result)
     */
    Map<String, Object> execute(CommandHandlerContext context);
}
