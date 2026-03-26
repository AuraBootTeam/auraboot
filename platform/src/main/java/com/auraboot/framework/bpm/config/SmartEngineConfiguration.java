package com.auraboot.framework.bpm.config;

import com.alibaba.smart.framework.engine.SmartEngine;
import com.alibaba.smart.framework.engine.configuration.ConfigurationOption;
import com.alibaba.smart.framework.engine.configuration.InstanceAccessor;
import com.alibaba.smart.framework.engine.configuration.ListenerExecutor;
import com.alibaba.smart.framework.engine.context.ExecutionContext;
import com.alibaba.smart.framework.engine.listener.Listener;
import com.alibaba.smart.framework.engine.model.assembly.ExtensionElementContainer;
import com.alibaba.smart.framework.engine.pvm.event.EventConstant;
import com.auraboot.framework.exception.BusinessException;
import com.alibaba.smart.framework.engine.configuration.ProcessEngineConfiguration;
import com.alibaba.smart.framework.engine.configuration.TaskAssigneeDispatcher;
import com.alibaba.smart.framework.engine.configuration.impl.DefaultProcessEngineConfiguration;
import com.alibaba.smart.framework.engine.configuration.impl.DefaultListenerExecutor;
import com.alibaba.smart.framework.engine.configuration.impl.DefaultSmartEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SmartEngine dual-mode configuration.
 *
 * Supports both Database Mode (for BPM approval workflows with full persistence)
 * and Custom Mode (for Automation workflows with in-memory execution).
 *
 * Storage mode is selected per-thread via StorageModeHolder:
 * - Default: DATABASE mode (full persistence to PostgreSQL)
 * - Custom: MEMORY mode (in-memory via PersisterSession, for Automation)
 */
@Slf4j
@Configuration
public class SmartEngineConfiguration implements ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Autowired(required = false)
    private TaskAssigneeDispatcher taskAssigneeDispatcher;

    @Autowired(required = false)
    private AuraTaskEventPublisher taskEventPublisher;

    @Bean
    public ProcessEngineConfiguration processEngineConfiguration() {
        log.info("Initializing SmartEngine ProcessEngineConfiguration (dual-mode)");

        DefaultProcessEngineConfiguration configuration = new DefaultProcessEngineConfiguration();
        configuration.setIdGenerator(new TimeBasedIdGenerator());
        configuration.setInstanceAccessor(new DualModeInstanceAccessor());
        configuration.getOptionContainer().put(ConfigurationOption.TRANSFER_ENABLED_OPTION);

        if (taskAssigneeDispatcher != null) {
            configuration.setTaskAssigneeDispatcher(taskAssigneeDispatcher);
            log.info("TaskAssigneeDispatcher configured: {}", taskAssigneeDispatcher.getClass().getSimpleName());
        } else {
            log.info("No TaskAssigneeDispatcher configured (bpm.test-assignee.enabled is not set to true)");
        }

        if (taskEventPublisher != null) {
            configuration.setTaskEventPublisher(taskEventPublisher);
            log.info("TaskEventPublisher configured: {}", taskEventPublisher.getClass().getSimpleName());
        }

        // Register global listener executor: wraps DefaultListenerExecutor (handles BPMN extensionElements)
        // and additionally dispatches to all Spring-managed Listener beans (e.g. ProcessEventListener).
        configuration.setListenerExecutor(new GlobalListenerExecutor());

        log.info("SmartEngine ProcessEngineConfiguration initialized (dual-mode: DATABASE + MEMORY)");
        return configuration;
    }

    @Bean
    public SmartEngine smartEngine(ProcessEngineConfiguration processEngineConfiguration) {
        log.info("Building SmartEngine instance (dual-mode)");

        SmartEngine smartEngine = new DefaultSmartEngine();
        smartEngine.init(processEngineConfiguration);

        log.info("SmartEngine instance built successfully (dual-mode)");
        return smartEngine;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * Global listener executor: first calls DefaultListenerExecutor (handles BPMN extensionElements),
     * then dispatches to every Spring-managed Listener bean (e.g. ProcessEventListener).
     * This wires Spring components into SmartEngine's event lifecycle without requiring
     * each BPMN process definition to declare them in extensionElements.
     */
    private class GlobalListenerExecutor implements ListenerExecutor {
        private final DefaultListenerExecutor delegate = new DefaultListenerExecutor();

        @Override
        public void execute(EventConstant event, ExtensionElementContainer container, ExecutionContext context) {
            // Invoke BPMN-declared listeners first
            delegate.execute(event, container, context);
            // Invoke all Spring-managed Listener beans
            try {
                applicationContext.getBeansOfType(Listener.class).values().forEach(listener -> {
                    try {
                        listener.execute(event, context);
                    } catch (Exception e) {
                        log.warn("Listener {} failed for event {}: {}",
                                listener.getClass().getSimpleName(), event, e.getMessage(), e);
                    }
                });
            } catch (Exception e) {
                log.warn("Failed to dispatch SmartEngine event {} to Spring listeners: {}", event, e.getMessage());
            }
        }
    }

    /**
     * Dual-mode instance accessor: resolves delegation classes from Spring context first,
     * then falls back to direct class instantiation for non-Spring-managed beans.
     */
    private class DualModeInstanceAccessor implements InstanceAccessor {
        @Override
        public Object access(String name) {
            try {
                return applicationContext.getBean(name);
            } catch (Exception e) {
                try {
                    return Class.forName(name).getDeclaredConstructor().newInstance();
                } catch (Exception ex) {
                    throw new BusinessException("Cannot resolve delegation: " + name, ex);
                }
            }
        }
    }
}
