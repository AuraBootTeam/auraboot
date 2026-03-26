package com.auraboot.framework.notification.service;

/**
 * Email sending abstraction.
 *
 * @since 5.1.0
 */
public interface EmailSender {

    /**
     * Send a plain HTML email.
     */
    void send(String to, String subject, String htmlBody);
}
