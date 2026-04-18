package com.auraboot.framework.integration.agent;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.auraboot.framework.agent.startup.DryRunHandlerAuditRunner;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.service.CommandHandler;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.meta.service.DryRunSafe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-59: verifies the dry-run handler audit walks beans, finds the
 * {@link DryRunSafe} marker, and emits the expected INFO/WARN log lines.
 */
@DisplayName("DryRunHandlerAuditRunner (PR-59)")
@Import(DryRunHandlerAuditRunnerTest.AuditTestBeans.class)
class DryRunHandlerAuditRunnerTest extends BaseIntegrationTest {

    @Autowired private DryRunHandlerAuditRunner runner;

    private ListAppender<ILoggingEvent> appender;
    private Logger targetLogger;

    @BeforeEach
    void attachAppender() {
        targetLogger = (Logger) LoggerFactory.getLogger(DryRunHandlerAuditRunner.class);
        appender = new ListAppender<>();
        appender.start();
        targetLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        targetLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("audit logs INFO for @DryRunSafe beans and WARN for unmarked beans")
    void audit_emits_both_log_lines() throws Exception {
        runner.run(new DefaultApplicationArguments());

        List<ILoggingEvent> events = appender.list;

        boolean sawSafeInfo = events.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains(AuditTestBeans.SafeHandler.class.getName())
                        && m.contains("@DryRunSafe")
                        && m.contains("will execute under dry-run"));
        boolean sawUnsafeWarn = events.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains(AuditTestBeans.UnsafeHandler.class.getName())
                        && m.contains("NOT @DryRunSafe")
                        && m.contains("SKIPPED under dry-run"));
        boolean sawSummary = events.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.startsWith("DryRunAudit summary:"));

        assertThat(sawSafeInfo).as("INFO line for @DryRunSafe bean").isTrue();
        assertThat(sawUnsafeWarn).as("WARN line for unmarked bean").isTrue();
        assertThat(sawSummary).as("summary line").isTrue();
    }

    // ---------- test beans ----------

    @TestConfiguration
    static class AuditTestBeans {
        @Bean
        SafeHandler dryRunAuditSafeHandler() { return new SafeHandler(); }

        @Bean
        UnsafeHandler dryRunAuditUnsafeHandler() { return new UnsafeHandler(); }

        @Component
        @DryRunSafe
        static class SafeHandler implements CommandHandler {
            @Override public String getHandlerName() { return "dryRunAuditSafeHandler"; }
            @Override public Map<String, Object> execute(CommandHandlerContext context) { return new HashMap<>(); }
        }

        @Component
        static class UnsafeHandler implements CommandHandler {
            @Override public String getHandlerName() { return "dryRunAuditUnsafeHandler"; }
            @Override public Map<String, Object> execute(CommandHandlerContext context) { return new HashMap<>(); }
        }
    }
}
