package com.auraboot.framework.bpm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.auraboot.framework.bpm.service.ProcessDeploymentService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G-B3: a failed deploy dumps the injected BPMN + the full cause chain to the log so the
 * (otherwise swallowed) SmartEngine root cause is recoverable.
 */
@Slf4j
@DisplayName("BPM deploy failure diagnostics (G-B3)")
class BpmDeployFailureDiagnosticsIT extends BaseIntegrationTest {

    @Autowired
    private ProcessDeploymentService deploymentService;

    private ListAppender<ILoggingEvent> appender;
    private Logger serviceLogger;

    /** A process that passes version injection (<process> present) but is rejected by the
     *  engine at deploy time: the sequenceFlow targets a node that does not exist. */
    private static final String BROKEN_BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         targetNamespace="http://auraboot.com/bpm">
              <process id="%s" name="Broken" isExecutable="true">
                <startEvent id="start" name="Start"/>
                <sequenceFlow id="f1" sourceRef="start" targetRef="does_not_exist"/>
              </process>
            </definitions>
            """;

    @BeforeEach
    void attachAppender() {
        serviceLogger = (Logger) LoggerFactory.getLogger(ProcessDeploymentService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        if (serviceLogger != null && appender != null) {
            serviceLogger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("deploy failure dumps the injected BPMN and the cause chain to the log")
    void deployFailure_dumpsInjectedBpmnAndCauseChain() {
        String processKey = "broken-" + System.nanoTime();
        ProcessDeploymentService.CreateProcessRequest req =
                new ProcessDeploymentService.CreateProcessRequest(
                        processKey, "Broken", "G-B3", "test",
                        String.format(BROKEN_BPMN, processKey),
                        null, null, null);
        BpmProcessDefinition def = deploymentService.create(req);

        // A broken process fails deploy. (AuraBoot BusinessException does not chain the JVM
        // cause, which is precisely why G-B3 logs the throwable directly — so the root cause
        // is recoverable from the log rather than the exception's getCause().)
        assertThatThrownBy(() -> deploymentService.deploy(def.getPid()))
                .as("a broken process must fail deploy")
                .isInstanceOf(BusinessException.class);

        String errorLogs = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(e -> e.getFormattedMessage() + " " + (e.getThrowableProxy() == null
                        ? "" : e.getThrowableProxy().getClassName() + ":" + e.getThrowableProxy().getMessage()))
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(errorLogs)
                .as("G-B3: the injected BPMN (containing the process key) must be dumped on deploy failure")
                .contains("Injected BPMN follows")
                .contains(processKey);
        assertThat(errorLogs)
                .as("G-B3: a cause chain line must be logged on deploy failure")
                .contains("cause chain");
        assertThat(errorLogs)
                .as("G-B3: the swallowed engine root cause must be recoverable from the log")
                .containsIgnoringCase("Parse process definition file failure");
    }
}
