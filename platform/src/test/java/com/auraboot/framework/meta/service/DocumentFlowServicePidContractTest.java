package com.auraboot.framework.meta.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentFlowServicePidContractTest {

    private final DocumentFlowService service = new DocumentFlowService(null, null, null);

    @Test
    void resolveExpressionUsesRecordPidOnly() {
        assertThat(service.resolveExpression("${recordPid}", Map.of(), "SRC-PID-1", null))
                .isEqualTo("SRC-PID-1");

        assertThat(service.resolveExpression("${record" + "Id}", Map.of(), "SRC-PID-1", null))
                .isEqualTo("${record" + "Id}");
    }
}
