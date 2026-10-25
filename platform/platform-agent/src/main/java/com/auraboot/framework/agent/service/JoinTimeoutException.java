package com.auraboot.framework.agent.service;

/**
 * Thrown by {@link ParentJoinService#joinChildRun(String, String, long)} when
 * the child run does not reach a terminal state inside the requested timeout.
 *
 * <p>Carries the parent / child run ids and the actual waited millis in the
 * message so caller logs can correlate. Unchecked because the join API is
 * meant to be called from inside an LLM tool / DSL workflow step where
 * checked-exception bookkeeping is just noise — callers can convert to a
 * tool-error result string at their boundary.
 */
public class JoinTimeoutException extends RuntimeException {

    private final String parentRunId;
    private final String childRunId;
    private final long waitedMillis;

    public JoinTimeoutException(String parentRunId, String childRunId, long waitedMillis) {
        super("ParentJoinService.joinChildRun timed out: parent=" + parentRunId
                + " child=" + childRunId + " waitedMs=" + waitedMillis);
        this.parentRunId = parentRunId;
        this.childRunId = childRunId;
        this.waitedMillis = waitedMillis;
    }

    public String getParentRunId() {
        return parentRunId;
    }

    public String getChildRunId() {
        return childRunId;
    }

    public long getWaitedMillis() {
        return waitedMillis;
    }
}
