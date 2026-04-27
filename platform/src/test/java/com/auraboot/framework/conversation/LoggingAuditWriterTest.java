package com.auraboot.framework.conversation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase B.3 unit test for {@link LoggingAuditWriter}. Verifies that
 * {@link TurnSideEffects.AuditWriter#writeFailure} emits a structured WARN log
 * line with all the {@link TurnContext} + {@link TurnOutcome.Failed} fields
 * downstream log aggregation needs.
 *
 * <p>Captures via Logback {@link ListAppender} attached to the
 * {@code LoggingAuditWriter} logger so we can assert message text + level
 * without depending on stdout / stderr.
 */
@DisplayName("LoggingAuditWriter — structured WARN log on failed turns")
class LoggingAuditWriterTest {

    private LoggingAuditWriter writer;
    private ListAppender<ILoggingEvent> appender;
    private Logger logbackLogger;

    @BeforeEach
    void attachAppender() {
        writer = new LoggingAuditWriter();
        logbackLogger = (Logger) LoggerFactory.getLogger(LoggingAuditWriter.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        if (logbackLogger != null && appender != null) {
            logbackLogger.detachAppender(appender);
        }
    }

    private TurnContext newCtx() {
        return new TurnContext(
                "01HW3K8XJZ",                      // turnId
                42L,                                 // tenantId
                100L,                                // userId
                200L,                                // humanMemberId
                7L,                                  // agentId
                "ch-1",                              // channelSessionId
                999L,                                // conversationId
                null, null, null,
                Instant.now());
    }

    @Test
    @DisplayName("writeFailure emits WARN log carrying turnId / tenantId / userId / error / cause")
    void writeFailure_emitsStructuredWarn() {
        TurnContext ctx = newCtx();
        TurnOutcome.Failed failed = new TurnOutcome.Failed(
                "LLM provider timed out",
                new RuntimeException("upstream connect-reset"));

        writer.writeFailure(ctx, failed);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent ev = appender.list.get(0);
        assertThat(ev.getLevel()).isEqualTo(Level.WARN);
        String formatted = ev.getFormattedMessage();
        assertThat(formatted).contains("turn-failure-audit");
        assertThat(formatted).contains("turnId=01HW3K8XJZ");
        assertThat(formatted).contains("tenantId=42");
        assertThat(formatted).contains("userId=100");
        assertThat(formatted).contains("memberId=200");
        assertThat(formatted).contains("conversationId=999");
        assertThat(formatted).contains("agentId=7");
        assertThat(formatted).contains("error=LLM provider timed out");
        assertThat(formatted).contains("causeClass=java.lang.RuntimeException");
        assertThat(formatted).contains("causeMessage=upstream connect-reset");
    }

    @Test
    @DisplayName("writeFailure with null cause -> 'causeClass=(none)' fallback")
    void writeFailure_nullCause_fallsBackToNone() {
        writer.writeFailure(newCtx(),
                new TurnOutcome.Failed("synthesized failure with no cause", null));

        assertThat(appender.list).hasSize(1);
        String formatted = appender.list.get(0).getFormattedMessage();
        assertThat(formatted).contains("causeClass=(none)");
        assertThat(formatted).contains("causeMessage=null");
    }

    @Test
    @DisplayName("writeFailure(null ctx) -> silently skips, no log emitted")
    void writeFailure_nullCtx_noLog() {
        writer.writeFailure(null, new TurnOutcome.Failed("err", null));
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("writeFailure(null failed) -> silently skips, no log emitted")
    void writeFailure_nullFailed_noLog() {
        writer.writeFailure(newCtx(), null);
        assertThat(appender.list).isEmpty();
    }
}
