package com.auraboot.framework.bpm.engine.config;

import com.auraboot.framework.bpm.engine.BpmEngine;
import com.auraboot.framework.bpm.engine.BpmEngineFactory;
import com.auraboot.framework.bpm.engine.adapter.SmartEngineBpmAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Auto-configuration for the BPM abstraction layer.
 * <p>
 * Registers the SmartEngine adapter by default and wires the
 * {@link BpmEngineFactory} that resolves engines by type.
 */
@Configuration
@EnableConfigurationProperties(BpmProperties.class)
public class BpmAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BpmAutoConfiguration.class);

    /**
     * Default SmartEngine adapter (registered unless another BpmEngine
     * of type "smartengine" is already present).
     */
    @Bean
    @ConditionalOnMissingBean(SmartEngineBpmAdapter.class)
    public SmartEngineBpmAdapter smartEngineBpmAdapter() {
        log.info("Registering SmartEngine BPM adapter");
        return new SmartEngineBpmAdapter();
    }

    /**
     * Factory that collects all {@link BpmEngine} beans and allows
     * lookup by engine type.
     */
    @Bean
    @ConditionalOnMissingBean(BpmEngineFactory.class)
    public BpmEngineFactory bpmEngineFactory(List<BpmEngine> engines) {
        Map<String, BpmEngine> registry = engines.stream()
                .collect(Collectors.toMap(BpmEngine::getEngineType, Function.identity()));

        log.info("BPM engine factory initialised with adapters: {}", registry.keySet());

        return engineType -> {
            BpmEngine engine = registry.get(engineType);
            if (engine == null) {
                throw new IllegalArgumentException(
                        "No BPM engine adapter registered for type: " + engineType
                                + ". Available: " + registry.keySet());
            }
            return engine;
        };
    }

    /**
     * Convenience bean: the "primary" {@link BpmEngine} as defined by
     * the {@code aura.bpm.engine} property.
     */
    @Bean
    @ConditionalOnMissingBean(BpmEngine.class)
    public BpmEngine bpmEngine(BpmEngineFactory factory, BpmProperties properties) {
        String type = properties.getEngine();
        log.info("Primary BPM engine type: {}", type);
        return factory.create(type);
    }
}
