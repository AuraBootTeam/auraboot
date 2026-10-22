package com.auraboot.framework.behavior.outcome;

import com.auraboot.framework.application.MetaApplication;
import com.auraboot.framework.behavior.mapper.BehaviorOutcomeOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** Full command/API path with only a test-owned fault after the actual outbox INSERT. */
@SpringBootTest(classes = MetaApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = "spring.main.allow-bean-definition-overriding=true")
@Import(AnalyticsCommandRollbackIT.FaultConfiguration.class)
@DirtiesContext
class AnalyticsCommandRollbackIT {
    @TestConfiguration
    static class FaultConfiguration {
        @Bean @Primary
        FaultPublisher faultPublisher(BehaviorOutcomeOutboxMapper mapper, ObjectMapper json) {
            return new FaultPublisher(mapper, json);
        }
    }

    static class FaultPublisher extends BehaviorOutcomePublisher {
        private volatile boolean failing;
        private final AtomicInteger injected = new AtomicInteger();
        FaultPublisher(BehaviorOutcomeOutboxMapper mapper, ObjectMapper json) { super(mapper, json); }
        void fail(boolean enabled) { failing = enabled; }
        int injectedCount() { return injected.get(); }
        @Override @Transactional(propagation = Propagation.MANDATORY)
        public boolean publish(BehaviorOutcomeEvent event) {
            boolean inserted = super.publish(event);
            if (failing && "analytics_business_command_committed".equals(event.getEventName())) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(inserted).isTrue();
                injected.incrementAndGet();
                throw new IllegalStateException("injected after analytics business outcome insert");
            }
            return inserted;
        }
    }

    @Autowired FaultPublisher fault;

    @ParameterizedTest
    @ValueSource(strings = {"create", "update", "delete"})
    void actualCommandRollsBackThenNormalCommandCommits(String operation) throws Exception {
        int initialFaultCount = fault.injectedCount();
        fault.fail(true);
        try {
            runApi(operation, true);
            assertThat(fault.injectedCount()).isEqualTo(initialFaultCount + 1);
        } finally {
            fault.fail(false);
        }
        runApi(operation, false);
        assertThat(fault.injectedCount()).isEqualTo(initialFaultCount + 1);
    }

    private void runApi(String operation, boolean expectRollback) throws Exception {
        Path web = Path.of(Objects.requireNonNull(System.getenv("AURA_ANALYTICS_WEB_ROOT")));
        Path evidence = Path.of(Objects.requireNonNull(System.getenv("AURA_ANALYTICS_ROLLBACK_EVIDENCE")));
        var process = new ProcessBuilder("pnpm", "exec", "playwright", "test", "-c", "playwright.noweb.config.ts",
                "tests/api/analytics-suggestion-commands.spec.ts", "--project=api", "--no-deps", "--workers=1",
                "--retries=0", "--reporter=line").directory(web.toFile()).redirectErrorStream(true)
                .redirectOutput(evidence.resolve(operation + (expectRollback ? "-rollback-api.log" : "-commit-api.log")).toFile());
        process.environment().put("AURA_ANALYTICS_COMMAND_OPERATION", operation);
        process.environment().put("AURA_ANALYTICS_EXPECT_COMMAND_ROLLBACK", Boolean.toString(expectRollback));
        Process child = process.start();
        boolean finished = child.waitFor(150, TimeUnit.SECONDS);
        if (!finished) child.destroy();
        assertThat(finished).as("API runner must finish; inspect the phase log").isTrue();
        assertThat(child.exitValue()).as("API phase exit code, rollback=" + expectRollback).isZero();
    }
}
