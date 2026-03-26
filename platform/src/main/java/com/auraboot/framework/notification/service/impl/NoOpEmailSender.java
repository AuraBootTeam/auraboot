package com.auraboot.framework.notification.service.impl;

import com.auraboot.framework.notification.service.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * No-op email sender used when SMTP is not configured.
 * Logs the email content instead of sending.
 * <p>
 * Always registered as a fallback. When the enterprise-comm module provides
 * SmtpEmailSender with @Primary, that takes precedence.
 *
 * @since 5.1.0
 */
@Slf4j
@Service
public class NoOpEmailSender implements EmailSender {

    @Override
    public void send(String to, String subject, String htmlBody) {
        log.info("Email send (no-op, SMTP not configured): to={}, subject={}", to, subject);
    }
}
