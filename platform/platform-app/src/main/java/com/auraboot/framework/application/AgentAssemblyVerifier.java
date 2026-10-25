package com.auraboot.framework.application;

import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.automation.executor.impl.CompositeActionExecutor;
import com.auraboot.framework.behavior.outcome.AnalyticsCommandOriginLookup;
import com.auraboot.framework.behavior.service.AnalyticsExecutionContext;
import com.auraboot.framework.behavior.service.AnalyticsExecutionReadModel;
import com.auraboot.framework.faq.FaqCandidateExtractor;
import com.auraboot.framework.faq.FaqKnowledgePublisher;
import com.auraboot.framework.i18n.service.TranslationGenerator;
import com.auraboot.framework.im.service.ImAgentDirectory;
import com.auraboot.framework.im.service.ImAiResponder;
import com.auraboot.framework.meta.ai.AiFieldGenerator;
import com.auraboot.framework.observability.LlmUsageQuery;
import com.auraboot.framework.plugin.extension.AiProviderAccessorFactory;
import com.auraboot.framework.plugin.extension.KnowledgeBaseAccessor;
import com.auraboot.framework.plugin.service.AgentDefinitionResourceLifecycle;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Optional;

/** Rejects missing or duplicated installed adapters before listeners and schedulers start. */
@Component
final class AgentAssemblyVerifier implements SmartInitializingSingleton {
    static final String DESCRIPTOR = "META-INF/auraboot/agent-module.properties";
    static final List<Class<?>> SINGLETON_PORTS = List.of(
            AgentDefinitionResourceLifecycle.class, ImAiResponder.class, ImAgentDirectory.class,
            AiFieldGenerator.class, AiProviderAccessorFactory.class, FaqCandidateExtractor.class,
            FaqKnowledgePublisher.class, TranslationGenerator.class, LlmUsageQuery.class,
            AnalyticsExecutionContext.class, AnalyticsExecutionReadModel.class, AnalyticsCommandOriginLookup.class,
            KnowledgeBaseAccessor.class);

    private final ConfigurableListableBeanFactory beans;
    private final ObjectProvider<AgentAssembly> selection;
    private volatile AgentAssembly verifiedAssembly;

    AgentAssemblyVerifier(ConfigurableListableBeanFactory beans, ObjectProvider<AgentAssembly> selection) {
        this.beans = beans;
        this.selection = selection;
    }

    @Override
    public void afterSingletonsInstantiated() {
        verifiedAssembly = null;
        AgentAssembly assembly = selection.getIfAvailable(() -> AgentAssembly.WITH_AGENT);
        boolean installed = assembly == AgentAssembly.WITH_AGENT;
        try {
            var descriptors = Collections.list(beans.getBeanClassLoader().getResources(DESCRIPTOR));
            if (descriptors.size() != (installed ? 1 : 0)) {
                throw new IllegalStateException("Agent assembly " + assembly + " has " + descriptors.size()
                        + " installation descriptors");
            }
            for (Class<?> port : SINGLETON_PORTS) {
                int count = beans.getBeanNamesForType(port).length;
                if (count != (installed ? 1 : 0)) {
                    throw new IllegalStateException("Agent assembly " + assembly + " requires "
                            + (installed ? 1 : 0) + " adapter for " + port.getName() + ", found " + count);
                }
            }
            long llmExecutors = beans.getBeansOfType(ActionExecutor.class).values().stream()
                    .filter(executor -> !(executor instanceof CompositeActionExecutor))
                    .filter(executor -> executor.supports("llm_call")).count();
            if (llmExecutors != (installed ? 1 : 0)) {
                throw new IllegalStateException("Agent assembly " + assembly + " has " + llmExecutors + " LLM action executors");
            }
            if (installed) {
                Properties descriptor = new Properties();
                try (var input = descriptors.get(0).openStream()) { descriptor.load(input); }
                if (!"1".equals(descriptor.getProperty("schemaVersion"))
                        || !"auraboot-agent".equals(descriptor.getProperty("id"))) {
                    throw new IllegalStateException("Invalid agent installation descriptor");
                }
                String contributors = descriptor.getProperty("contributors");
                if (contributors == null || contributors.isBlank()) {
                    throw new IllegalStateException("Agent contributor declarations are missing");
                }
                for (String name : contributors.split(",")) {
                    Class<?> contributor = Class.forName(name.trim(), false, beans.getBeanClassLoader());
                    int count = beans.getBeanNamesForType(contributor).length;
                    if (count == 0) throw new IllegalStateException("Agent contributor " + name + " has no registrations");
                }
            }
        } catch (IOException | ClassNotFoundException failure) {
            throw new IllegalStateException("Cannot verify the selected agent assembly", failure);
        }
        verifiedAssembly = assembly;
    }

    /** Empty until the entire installation and adapter closure has passed validation. */
    Optional<AgentAssembly> verifiedAssembly() {
        return Optional.ofNullable(verifiedAssembly);
    }
}
