package com.auraboot.framework.application.security;

/** Expected fail-closed rejection from a module-owned external API key policy. */
public class ExternalApiKeyException extends RuntimeException {

    private final int status;
    private final String code;

    public ExternalApiKeyException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
