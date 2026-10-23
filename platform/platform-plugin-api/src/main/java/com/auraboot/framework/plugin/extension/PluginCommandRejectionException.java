package com.auraboot.framework.plugin.extension;

import java.util.Objects;

/** Transport-neutral rejection of an authorized command by a business rule. */
public final class PluginCommandRejectionException extends RuntimeException {
    public enum Code { INVALID_ARGUMENT, BUSINESS_RULE_BLOCKED }

    private final Code code;
    private final String messageKey;
    private final Object[] messageArgs;

    public PluginCommandRejectionException(Code code, String messageKey) {
        this(code, messageKey, new Object[0]);
    }

    public PluginCommandRejectionException(Code code, String messageKey, Object... messageArgs) {
        super("$i18n:" + Objects.requireNonNull(messageKey, "messageKey"));
        this.code = Objects.requireNonNull(code, "code");
        if (messageKey.isBlank()) throw new IllegalArgumentException("messageKey must not be blank");
        this.messageKey = messageKey;
        this.messageArgs = messageArgs == null ? new Object[0] : messageArgs.clone();
    }

    public Code code() { return code; }
    public String messageKey() { return messageKey; }
    public Object[] messageArgs() { return messageArgs.clone(); }
}
