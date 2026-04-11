package com.auraboot.framework.notification.service.impl;

import com.auraboot.framework.notification.service.EmailSender;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * SMTP-based email sender using Spring Boot Mail (JavaMailSender).
 *
 * <p>Activated when {@code spring.mail.host} is configured.
 * When active, this bean replaces the {@link NoOpEmailSender}.
 *
 * <p>Configuration example (application.yml):
 * <pre>
 * spring:
 *   mail:
 *     host: smtp.gmail.com
 *     port: 587
 *     username: your@gmail.com
 *     password: app-password
 *     properties:
 *       mail.smtp.auth: true
 *       mail.smtp.starttls.enable: true
 * auraboot:
 *   mail:
 *     from: noreply@auraboot.com
 * </pre>
 *
 * @since 7.2.0
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.mail.host")
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpEmailSender(JavaMailSender mailSender,
                           @org.springframework.beans.factory.annotation.Value("${auraboot.mail.from:noreply@auraboot.com}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        log.info("SmtpEmailSender initialized: from={}", fromAddress);
    }

    @Override
    public void send(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("Email sent to {} — subject: {}", to, subject);
        } catch (MessagingException e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage(), e);
            throw new RuntimeException("Failed to send email: " + e.getMessage(), e);
        }
    }
}
