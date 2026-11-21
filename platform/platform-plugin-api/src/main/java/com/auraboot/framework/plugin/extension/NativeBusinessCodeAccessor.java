package com.auraboot.framework.plugin.extension;

/** Reuses one explicitly scoped published native command's auto_generate rule; grants no CRUD or command execution. */
public interface NativeBusinessCodeAccessor {
    String SETTINGS_KEY="__nativeBusinessCodeAccessor";
    String SCOPE_HANDLER_PARAM="nativeBusinessCodes";
    /** Propose the next native business code within the caller's transaction; the caller must persist it atomically. */
    String generate(String sourceCommandCode,String sourceFieldCode);
}
