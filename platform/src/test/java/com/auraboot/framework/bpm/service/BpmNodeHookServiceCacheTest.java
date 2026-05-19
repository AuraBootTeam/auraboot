package com.auraboot.framework.bpm.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmNodeHook;
import com.auraboot.framework.bpm.mapper.BpmNodeHookMapper;
import com.auraboot.framework.bpm.rule.DroolsEngineService;
import com.auraboot.framework.meta.service.CommandExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BpmNodeHookServiceCacheTest {

    @Mock
    private BpmNodeHookMapper hookMapper;
    @Mock
    private DroolsEngineService droolsEngineService;
    @Mock
    private CommandExecutor commandExecutor;
    @Mock
    private RestTemplate restTemplate;

    private BpmNodeHookService hookService;

    @BeforeEach
    void setUp() {
        hookService = new BpmNodeHookService(
                hookMapper,
                droolsEngineService,
                commandExecutor,
                restTemplate);
        MetaContext.setContext(1L, 10L, "u-10", "tester");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void getHooksLoadsAllProcessHooksOnceAndFiltersInMemory() {
        BpmNodeHook preCheck = hook("proc", "approve", "pre_check", true, 1);
        BpmNodeHook disabledPreCheck = hook("proc", "approve", "pre_check", false, 2);
        BpmNodeHook postAction = hook("proc", "notify", "post_action", true, 1);
        doReturn(List.of(preCheck, disabledPreCheck, postAction))
                .when(hookMapper).findByProcessKey(1L, "proc");

        assertThat(hookService.getHooks("proc", "approve", "pre_execute"))
                .containsExactly(preCheck);
        assertThat(hookService.getHooks("proc", "notify", "post_action"))
                .containsExactly(postAction);

        verify(hookMapper, times(1)).findByProcessKey(1L, "proc");
        verify(hookMapper, never()).findHooks(any(), any(), any(), any());
    }

    @Test
    void createHookInvalidatesCachedProcessHooks() {
        BpmNodeHook existing = hook("proc", "approve", "pre_check", true, 1);
        BpmNodeHook created = hook("proc", "review", "pre_check", true, 2);
        doReturn(List.of(existing), List.of(existing, created))
                .when(hookMapper).findByProcessKey(1L, "proc");
        doAnswer(invocation -> {
            BpmNodeHook hook = invocation.getArgument(0);
            hook.setId(99L);
            return 1;
        }).when(hookMapper).insert(any(BpmNodeHook.class));

        assertThat(hookService.getHooks("proc", "approve", "pre_check"))
                .containsExactly(existing);
        hookService.createHook(created);

        assertThat(hookService.getHooks("proc", "review", "pre_check"))
                .containsExactly(created);
        verify(hookMapper, times(2)).findByProcessKey(1L, "proc");
        verify(hookMapper, never()).findHooks(any(), any(), any(), any());
    }

    private static BpmNodeHook hook(String processKey,
                                   String nodeId,
                                   String hookType,
                                   boolean enabled,
                                   int executionOrder) {
        return BpmNodeHook.builder()
                .tenantId(1L)
                .processKey(processKey)
                .nodeId(nodeId)
                .hookType(hookType)
                .hookConfig(Map.of("type", "script", "script", "true"))
                .failStrategy("block")
                .async(false)
                .enabled(enabled)
                .executionOrder(executionOrder)
                .build();
    }
}
