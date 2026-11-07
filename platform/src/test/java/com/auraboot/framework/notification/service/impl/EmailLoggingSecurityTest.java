package com.auraboot.framework.notification.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailLoggingSecurityTest {

    @Test
    void noOpDoesNotLogRecipientsOrMessageContent() {
        assertLogsExcludePrivateContent(NoOpEmailSender.class, () ->
                new NoOpEmailSender().send("private-recipient@example.com", "private-subject", "private-body"));
    }

    @Test
    void smtpSuccessAndFailureKeepPrivateContentOutOfLogEvents() {
        JavaMailSender mail = mock(JavaMailSender.class);
        when(mail.createMimeMessage()).thenAnswer(ignored -> new MimeMessage(Session.getInstance(new Properties())));
        assertLogsExcludePrivateContent(SmtpEmailSender.class, () -> {
            var sender = new SmtpEmailSender(mail, "private-from@example.com");
            sender.send("private-recipient@example.com", "private-subject", "private-body");
            assertThatThrownBy(() -> sender.send("private-invalid address!!", "private-subject", "private-body"))
                    .isInstanceOf(RuntimeException.class);
        });
    }

    private void assertLogsExcludePrivateContent(Class<?> type, Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            assertThat(appender.list).isNotEmpty();
            for (ILoggingEvent event : appender.list) {
                assertThat(event.getFormattedMessage()).doesNotContain("private-", "example.com");
                assertThat(event.getThrowableProxy()).isNull();
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
