package com.auraboot.framework.agentchat.router;

public enum AgentReplyMode {
    MENTION("mention"),
    ALWAYS("always"),
    OFF("off");

    private final String code;

    AgentReplyMode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static AgentReplyMode fromCode(String code) {
        if (code == null) {
            return MENTION; // default
        }
        for (AgentReplyMode mode : values()) {
            if (mode.code.equals(code)) {
                return mode;
            }
        }
        return MENTION;
    }
}
