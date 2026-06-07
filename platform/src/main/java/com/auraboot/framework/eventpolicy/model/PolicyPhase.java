package com.auraboot.framework.eventpolicy.model;

/** When a policy runs relative to the form/data transaction (docs/2.md §3, §9). */
public enum PolicyPhase {
    /** Synchronous, in-transaction; may block the submit (validation / field patch). */
    BEFORE_SUBMIT,
    /** After commit; side effects run out-of-transaction (notify / start process / create task). */
    AFTER_COMMIT,
    /** Driven by an async worker (e.g. SLA breach escalation). */
    ASYNC_WORKER
}
