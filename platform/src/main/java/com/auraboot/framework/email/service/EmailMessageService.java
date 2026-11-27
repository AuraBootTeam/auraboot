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

    /** Paged query with a caller-built condition (read paths moved from the controller). */
    com.baomidou.mybatisplus.core.metadata.IPage<com.auraboot.framework.email.model.EmailMessage> selectPage(
            com.baomidou.mybatisplus.core.metadata.IPage<com.auraboot.framework.email.model.EmailMessage> page,
            com.baomidou.mybatisplus.core.conditions.Wrapper<com.auraboot.framework.email.model.EmailMessage> wrapper);

    /** All messages of a thread, newest first. */
    java.util.List<com.auraboot.framework.email.model.EmailMessage> findThread(Long tenantId, String threadId);

    /** Message by id, or null. */
    com.auraboot.framework.email.model.EmailMessage findById(Long id);
}
