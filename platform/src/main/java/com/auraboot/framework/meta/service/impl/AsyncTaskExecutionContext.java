package com.auraboot.framework.meta.service.impl;

/** Host-only claimed-row identity; task input cannot choose or install execution authority. */
public final class AsyncTaskExecutionContext {
    private AsyncTaskExecutionContext() {}
    public record Snapshot(Long taskId,String taskCode,Long tenantId,Long createdBy,String token) {}
    private static final ThreadLocal<Snapshot> CURRENT=new ThreadLocal<>();

    public static Snapshot require() {
        Snapshot snapshot=CURRENT.get();
        if(snapshot==null) throw new IllegalStateException("No claimed asynchronous execution context");
        return snapshot;
    }
    interface Scope extends AutoCloseable { @Override void close(); }
    static Scope enter(Snapshot snapshot) {
        if(snapshot==null || snapshot.taskId()==null || snapshot.tenantId()==null
            || snapshot.taskCode()==null || snapshot.token()==null || snapshot.token().isBlank())
            throw new IllegalArgumentException("A claimed task identity and token are required");
        Snapshot previous=CURRENT.get();CURRENT.set(snapshot);
        return ()->{if(previous==null) CURRENT.remove();else CURRENT.set(previous);};
    }
}
