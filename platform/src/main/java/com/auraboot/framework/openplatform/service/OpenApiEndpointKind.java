package com.auraboot.framework.openplatform.service;

import java.util.Set;

/** Finite machine adapters delegate to existing authorized business service boundaries. */
public enum OpenApiEndpointKind {
    RESOURCE_LIST("resource.list", "GET", false, "list"),
    RESOURCE_GET("resource.get", "GET", true, "get"),
    RESOURCE_CREATE("resource.create", "POST", false, "create"),
    RESOURCE_UPDATE("resource.update", "PATCH", true, "update"),
    RESOURCE_DELETE("resource.delete", "DELETE", true, "delete"),
    ACTION("action.execute", "POST", false, "execute"),
    QUERY("query.execute", "POST", false, "execute"),
    INTEGRATION("integration.execute", "POST", false, "execute");

    private final String code;
    private final String method;
    private final boolean target;
    private final String operation;
    OpenApiEndpointKind(String code, String method, boolean target, String operation) {
        this.code = code; this.method = method; this.target = target; this.operation = operation;
    }
    public String code() { return code; }
    public String method() { return method; }
    public boolean target() { return target; }
    public String operation() { return operation; }
    public boolean resource() { return this != ACTION && this != QUERY && this != INTEGRATION; }
    public boolean write() { return Set.of(RESOURCE_CREATE, RESOURCE_UPDATE, RESOURCE_DELETE).contains(this); }
    public static OpenApiEndpointKind fromCode(String code) {
        for (var kind : values()) if (kind.code.equals(code)) return kind;
        throw new IllegalArgumentException("Unregistered machine endpoint adapter");
    }
}
