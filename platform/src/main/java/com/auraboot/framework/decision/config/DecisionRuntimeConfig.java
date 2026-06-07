package com.auraboot.framework.decision.config;

import com.auraboot.framework.decision.adapter.DecisionAdapter;
import com.auraboot.framework.decision.adapter.DecisionTableAdapter;
import com.auraboot.framework.decision.adapter.SimpleConditionAdapter;
import com.auraboot.framework.decision.runtime.DecisionRuntime;
import com.auraboot.framework.decision.runtime.DefaultDecisionRuntime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring configuration for the Decision Runtime beans.
 *
 * <p>Wires {@link SimpleConditionAdapter} and produces a {@link DecisionRuntime}
 * ({@link DefaultDecisionRuntime}) from the full adapter list.
 * Additional adapters (DMN, DRL, …) can be registered by adding more
 * {@link DecisionAdapter} beans — {@link DefaultDecisionRuntime} receives the full
 * Spring-managed list.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Configuration
public class DecisionRuntimeConfig {

    @Bean
    public SimpleConditionAdapter simpleConditionAdapter() {
        return new SimpleConditionAdapter();
    }

    @Bean
    public DecisionTableAdapter decisionTableAdapter() {
        return new DecisionTableAdapter();
    }

    @Bean
    public com.auraboot.framework.decision.adapter.CrossFieldDecisionAdapter crossFieldDecisionAdapter() {
        return new com.auraboot.framework.decision.adapter.CrossFieldDecisionAdapter();
    }

    /**
     * Build the runtime from all {@link DecisionAdapter} beans.
     * Spring injects the full list automatically via {@code List<DecisionAdapter>}.
     */
    @Bean
    public DecisionRuntime decisionRuntime(List<DecisionAdapter> adapters) {
        return new DefaultDecisionRuntime(adapters);
    }
}
