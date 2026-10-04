package com.auraboot.framework.plugin.extension;

import java.util.Objects;

/** Transport-neutral rejection of an authorized command by a business rule. */
public final class PluginCommandRejectionException extends RuntimeException {
    public enum Code { INVALID_ARGUMENT, BUSINESS_RULE_BLOCKED }

    private final Code code;
    private final String messageKey;

    public PluginCommandRejectionException(Code code, String messageKey) {
        super("$i18n:" + Objects.requireNonNull(messageKey, "messageKey"));
        this.code = Objects.requireNonNull(code, "code");
        if (messageKey.isBlank()) throw new IllegalArgumentException("messageKey must not be blank");
        this.messageKey = messageKey;
    }

    public Code code() { return code; }
    public String messageKey() { return messageKey; }
}
