package com.auraboot.framework.notification.service.impl;

import com.auraboot.framework.notification.service.EmailSender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("EmailSender bean selection")
class EmailSenderBeanSelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EmailSenderTestConfig.class);

    @Test
    void defaultsToNoOpWhenSmtpIsNotConfigured() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(EmailSender.class);
            assertThat(context.getBean(EmailSender.class)).isInstanceOf(NoOpEmailSender.class);
        });
    }

    @Test
    void usesSmtpSenderAsPrimaryEmailSenderWhenSmtpIsConfigured() {
        contextRunner
                .withPropertyValues(
                        "spring.mail.host=smtp.example.test",
                        "auraboot.mail.from=noreply@example.test")
                .run(context -> {
                    assertThat(context.getBean(EmailSender.class)).isInstanceOf(SmtpEmailSender.class);
                    assertThat(context.getBeansOfType(EmailSender.class).values())
                            .hasAtLeastOneElementOfType(SmtpEmailSender.class)
                            .hasAtLeastOneElementOfType(NoOpEmailSender.class);
                });
    }

    @Configuration
    @Import({NoOpEmailSender.class, SmtpEmailSender.class})
    static class EmailSenderTestConfig {
        @Bean
        JavaMailSender javaMailSender() {
            return mock(JavaMailSender.class);
        }
    }
}
