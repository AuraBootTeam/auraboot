package com.auraboot.framework.email.service;

/**
 * Write operations on email message records.
 * <p>
 * Controllers must not touch the mapper layer directly (ArchUnit rule 2);
 * this thin service carries the record-state writes they used to perform.
 */
public interface EmailMessageService {

    /**
     * Marks the message with the given database id as read.
     *
     * @param id message database id
     */
    void markRead(Long id);
}
