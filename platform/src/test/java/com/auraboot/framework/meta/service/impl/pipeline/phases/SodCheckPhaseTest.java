package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.SodService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SodCheckPhaseTest {

    @Mock
    private SodService sodService;

    private SodCheckPhase phase;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(99L, 10L, "usr_10", "Alice");
        phase = new SodCheckPhase();
        ReflectionTestUtils.setField(phase, "sodService", sodService);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void executePassesNonNumericTargetRecordIdAsEntityPid() {
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setTargetRecordId("pur_01KPID");

        CommandDefinition command = new CommandDefinition();
        command.setModelCode("mkt_purchase");

        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode("mkt:approve_purchase")
                .request(request)
                .tenantId(99L)
                .userId(10L)
                .startTime(System.currentTimeMillis())
                .build();
        ctx.setCommand(command);

        phase.execute(ctx);

        verify(sodService).checkSod(
                "mkt:approve_purchase",
                10L,
                "Alice",
                "mkt_purchase",
                null,
                "pur_01KPID");
    }
}
