package com.auraboot.framework.meta.transaction;

import java.util.Map;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit server-owned transaction admission; payloads never select isolation. */
public final class CommandTransactionIsolationPolicy {
    private CommandTransactionIsolationPolicy() {}
    public static boolean requiresSerializable(Map<?,?> executionConfig) {
        if(executionConfig==null || !(executionConfig.get("handlerParams") instanceof Map<?,?> parameters)
                || !parameters.containsKey("transactionIsolation"))return false;
        if(!"serializable".equals(parameters.get("transactionIsolation")))
            throw new IllegalArgumentException("COMMAND_TRANSACTION_POLICY_INVALID");
        Object async=parameters.get("async");
        if(Boolean.TRUE.equals(async) || "true".equalsIgnoreCase(String.valueOf(async)))
            throw new IllegalArgumentException("COMMAND_TRANSACTION_POLICY_ASYNC_UNSUPPORTED");
        return true;
    }
    public static void assertActive(Map<?,?> executionConfig) {
        if(requiresSerializable(executionConfig) && (!TransactionSynchronizationManager.isActualTransactionActive()
                || !Integer.valueOf(TransactionDefinition.ISOLATION_SERIALIZABLE).equals(
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())))
            throw new IllegalStateException("COMMAND_SERIALIZABLE_TRANSACTION_REQUIRED");
    }
}
