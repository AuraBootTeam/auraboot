package com.auraboot.framework.eventpolicy.config;

import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.executor.IdempotencyStore;
import com.auraboot.framework.eventpolicy.executor.PolicyExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires the {@link PolicyExecutor} from all registered {@link ActionHandler} beans plus the
 * DB-backed {@link IdempotencyStore}. Domain handlers (NOTIFY / START_PROCESS / CREATE_TASK / ...)
 * register as Spring beans and are auto-collected here — a follow-on slice adds them; today the
 * executor is exercised via tests with handlers supplied directly.
 */
@Configuration
public class EventPolicyExecutorConfig {

    @Bean
    public PolicyExecutor policyExecutor(ObjectProvider<ActionHandler> handlers, IdempotencyStore idempotencyStore) {
        // resolve handlers lazily per execution (a snapshot at bean creation can miss handler beans
        // depending on init order — this bit the test ActionHandler collection)
        return new PolicyExecutor(() -> handlers.stream().collect(java.util.stream.Collectors.toList()), idempotencyStore);
    }
}
