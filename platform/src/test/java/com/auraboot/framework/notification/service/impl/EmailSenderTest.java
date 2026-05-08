package com.auraboot.framework.notification.service.impl;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests covering both NoOpEmailSender (logging-only) and SmtpEmailSender
 * (JavaMailSender delegation + error path).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmailSender impls")
class EmailSenderTest {

    @Nested
    @DisplayName("NoOpEmailSender")
    class NoOp {
        @Test
        @DisplayName("send is a logging no-op")
        void noOp() {
            NoOpEmailSender sender = new NoOpEmailSender();
            // should not throw and should accept null/empty
            sender.send("to@x.com", "Hello", "<p>body</p>");
            sender.send(null, null, null);
        }
    }

    @ExtendWith(MockitoExtension.class)
    @Nested
    @DisplayName("SmtpEmailSender")
    class Smtp {

        @Mock
        JavaMailSender mailSender;

        private MimeMessage createMessage() {
            return new MimeMessage(Session.getInstance(new Properties()));
        }

        @Test
        @DisplayName("send delegates to JavaMailSender with from/to/subject/body")
        void sendOk() throws Exception {
            MimeMessage msg = createMessage();
            when(mailSender.createMimeMessage()).thenReturn(msg);

            SmtpEmailSender sender = new SmtpEmailSender(mailSender, "noreply@example.com");
            sender.send("rcpt@x.com", "Hi", "<p>body</p>");

            ArgumentCaptor<MimeMessage> cap = ArgumentCaptor.forClass(MimeMessage.class);
            verify(mailSender).send(cap.capture());
            MimeMessage sent = cap.getValue();
            assertEquals(1, sent.getAllRecipients().length);
            assertEquals("rcpt@x.com", sent.getAllRecipients()[0].toString());
            assertEquals("Hi", sent.getSubject());
        }

        @Test
        @DisplayName("send wraps invalid recipient as RuntimeException")
        void sendFailureWraps() {
            MimeMessage msg = createMessage();
            when(mailSender.createMimeMessage()).thenReturn(msg);

            SmtpEmailSender sender = new SmtpEmailSender(mailSender, "noreply@example.com");
            // MimeMessageHelper.setTo will throw AddressException (subclass of MessagingException)
            // for an invalid address — caught and rethrown as RuntimeException.
            assertThrows(RuntimeException.class,
                    () -> sender.send("not a valid address!!", "Hi", "body"));
        }
    }
}
