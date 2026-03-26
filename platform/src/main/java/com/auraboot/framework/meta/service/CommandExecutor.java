package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;

/**
 * Command Executor interface for executing commands through the pipeline.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
public interface CommandExecutor {

    /**
     * Execute a command by code
     *
     * @param commandCode the command definition code
     * @param request the execution request with payload and options
     * @return execution result
     */
    CommandExecuteResult execute(String commandCode, CommandExecuteRequest request);
}
