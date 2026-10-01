package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import com.auraboot.framework.plugin.extension.CommandDispatcherAccessor;

import java.util.Map;

/**
 * Host-side bridge that lets a plugin command handler chain a follow-up command
 * through the real command pipeline. Mirrors the request shape the internal
 * automation callers use ({@code targetRecordId}/{@code targetRecordPid} both
 * populated), so the nested command is validated, invariant-checked, evented,
 * and audited exactly like an externally invoked one.
 */
public class CommandDispatcherAccessorImpl implements CommandDispatcherAccessor {

    private final CommandExecutor commandExecutor;

    public CommandDispatcherAccessorImpl(CommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor;
    }

    @Override
    public Object execute(String commandCode, String modelCode, String recordPid,
                          Map<String, Object> payload) {
        if (commandExecutor == null || commandCode == null || commandCode.isBlank()) {
            return null;
        }
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setPayload(payload != null ? payload : Map.of());
        request.setTargetRecordId(recordPid);
        request.setTargetRecordPid(recordPid);
        try {
            CommandExecuteResult result = commandExecutor.execute(commandCode, request);
            return result == null ? null : result.getData();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Nested command dispatch failed: " + commandCode, e);
        }
    }
}
