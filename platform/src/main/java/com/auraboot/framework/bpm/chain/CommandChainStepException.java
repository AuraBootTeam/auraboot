package com.auraboot.framework.bpm.chain;

/**
 * Exception thrown when a command chain step fails.
 * Carries the step context for logging and compensation.
 *
 * @author AuraBoot Team
 * @since 3.0.0
 */
public class CommandChainStepException extends RuntimeException {

    private final String nodeId;
    private final String commandCode;

    public CommandChainStepException(String nodeId, String commandCode, String message) {
        super(String.format("Chain step [%s] command [%s] failed: %s", nodeId, commandCode, message));
        this.nodeId = nodeId;
        this.commandCode = commandCode;
    }

    public CommandChainStepException(String nodeId, String commandCode, String message, Throwable cause) {
        super(String.format("Chain step [%s] command [%s] failed: %s", nodeId, commandCode, message), cause);
        this.nodeId = nodeId;
        this.commandCode = commandCode;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getCommandCode() {
        return commandCode;
    }
}
