package com.auraboot.framework.bpm.audit;

/**
 * Canonical audit operation codes written to BpmAuditRecord.operation.
 * All values are lowercase per project DB convention (no enum.name() storage).
 */
public enum BpmAuditOperation {
    PROCESS_START("process_start"),
    TASK_APPROVE("task_approve"),
    TASK_REJECT("task_reject"),
    TASK_ADD_SIGN("task_add_sign"),
    TASK_TRANSFER("task_transfer"),
    WITHDRAW("withdraw"),
    CC("cc");

    private final String code;

    BpmAuditOperation(String code) { this.code = code; }

    public String code() { return code; }

    public boolean matches(String s) { return code.equalsIgnoreCase(s); }
}
