package com.auraboot.framework.meta.context;

/**
 * ThreadLocal flag for sandbox/dry-run mode.
 * When active, event publishing, webhook dispatch, and automation triggers are suppressed
 * (command pipeline stages 15-18).
 */
public final class SandboxContext {

    private static final ThreadLocal<Boolean> SANDBOX_MODE = ThreadLocal.withInitial(() -> false);

    private SandboxContext() {}

    public static void enterSandbox() {
        SANDBOX_MODE.set(true);
    }

    public static void exitSandbox() {
        SANDBOX_MODE.remove();
    }

    public static boolean isSandboxMode() {
        return Boolean.TRUE.equals(SANDBOX_MODE.get());
    }
}
