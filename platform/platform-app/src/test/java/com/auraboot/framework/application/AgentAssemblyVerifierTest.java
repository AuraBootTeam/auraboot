package com.auraboot.framework.application;

import com.auraboot.framework.agent.integration.bootstrap.AgentTemplateSeeder;
import com.auraboot.framework.agent.integration.bootstrap.AgentTenantSkillInitializer;
import com.auraboot.framework.agent.integration.context.AgentThreadContextContributor;
import com.auraboot.framework.automation.executor.ActionExecutor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Cardinality checks run before accepting business work, for both immutable launcher selections. */
class AgentAssemblyVerifierTest {
    static Stream<Class<?>> ports() { return AgentAssemblyVerifier.SINGLETON_PORTS.stream(); }
    static Stream<Class<?>> contributors() {
        return Stream.of(AgentThreadContextContributor.class, AgentTenantSkillInitializer.class, AgentTemplateSeeder.class);
    }

    @Test
    void baseHasNoAgentDescriptorOrAdapters() {
        var beans = new DefaultListableBeanFactory();
        beans.setBeanClassLoader(descriptors(false, false));
        beans.registerSingleton("selection", AgentAssembly.BASE);
        assertThat(verifyAssembly(beans).verifiedAssembly()).contains(AgentAssembly.BASE);
    }

    @Test
    void unrelatedContributorsRemainLegalInTheFullComposition() {
        var beans = full();
        beans.registerSingleton("otherContextContributor", mock(com.auraboot.framework.event.context.ThreadContextContributor.class));
        verifyAssembly(beans);
    }

    @Test
    void contributorCollectionsRetainTheirLegalMultipleRegistrationSemantics() {
        var beans = full();
        beans.registerSingleton("anotherAgentContextContributor", mock(AgentThreadContextContributor.class));
        verifyAssembly(beans);
    }

    @Test
    void fullRetainsEveryAdapterAndContributor() {
        assertThat(verifyAssembly(full()).verifiedAssembly()).contains(AgentAssembly.WITH_AGENT);
    }

    @Test
    void discoveryIsUnavailableBeforeVerificationAndAfterAnyFailedRevalidation() {
        var beans = full();
        var verifier = new AgentAssemblyVerifier(beans, beans.getBeanProvider(AgentAssembly.class));
        assertThat(verifier.verifiedAssembly()).isEmpty();
        verifier.afterSingletonsInstantiated();
        assertThat(verifier.verifiedAssembly()).contains(AgentAssembly.WITH_AGENT);
        beans.destroySingleton("llm");
        assertThatThrownBy(verifier::afterSingletonsInstantiated).hasMessageContaining("0 LLM action executors");
        assertThat(verifier.verifiedAssembly()).isEmpty();
    }

    @Test
    void baseCannotPretendAnInstalledLibraryIsAbsent() {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("selection", AgentAssembly.BASE);
        assertThatThrownBy(() -> verifyAssembly(beans)).hasMessageContaining("installation descriptors");
    }

    @Test
    void fullCannotSilentlyBecomeBaseWhenItsLibraryIsMissing() {
        var beans = full();
        beans.setBeanClassLoader(descriptors(false, false));
        assertThatThrownBy(() -> verifyAssembly(beans)).hasMessageContaining("installation descriptors");
    }

    @Test
    void duplicateLibrariesFailEvenWhenTheirAdaptersAreUnique() {
        var beans = full();
        beans.setBeanClassLoader(descriptors(true, true));
        assertThatThrownBy(() -> verifyAssembly(beans)).hasMessageContaining("2 installation descriptors");
    }

    @ParameterizedTest
    @MethodSource("ports")
    void fullRejectsEachMissingSingletonPort(Class<?> port) {
        var beans = full();
        beans.destroySingleton(port.getName());
        assertThatThrownBy(() -> verifyAssembly(beans)).hasMessageContaining(port.getName()).hasMessageContaining("found 0");
    }

    @ParameterizedTest
    @MethodSource("ports")
    void fullRejectsEachDuplicatedSingletonPort(Class<?> port) {
        var beans = full();
        beans.registerSingleton("duplicate", mock(port));
        assertThatThrownBy(() -> verifyAssembly(beans)).hasMessageContaining(port.getName()).hasMessageContaining("found 2");
    }

    @ParameterizedTest
    @MethodSource("ports")
    void baseRejectsAnAdapterWithoutAnInstallationDescriptor(Class<?> port) {
        var beans = new DefaultListableBeanFactory();
        beans.setBeanClassLoader(descriptors(false, false));
        beans.registerSingleton("selection", AgentAssembly.BASE);
        beans.registerSingleton(port.getName(), mock(port));
        assertThatThrownBy(() -> verifyAssembly(beans)).hasMessageContaining(port.getName()).hasMessageContaining("found 1");
    }

    @ParameterizedTest
    @MethodSource("contributors")
    void fullRequiresTheAgentContributorWithoutBanningOtherContributors(Class<?> contributor) {
        var beans = full();
        beans.destroySingleton(contributor.getName());
        assertThatThrownBy(() -> verifyAssembly(beans)).hasMessageContaining(contributor.getName());
    }

    @Test
    void fullRejectsMissingAndDuplicateLlmExecutors() {
        var beans = full();
        beans.destroySingleton("llm");
        assertThatThrownBy(() -> verifyAssembly(beans)).hasMessageContaining("0 LLM action executors");
        beans.registerSingleton("llm", llm());
        beans.registerSingleton("duplicateLlm", llm());
        assertThatThrownBy(() -> verifyAssembly(beans)).hasMessageContaining("2 LLM action executors");
    }

    private DefaultListableBeanFactory full() {
        var beans = new DefaultListableBeanFactory();
        AgentAssemblyVerifier.SINGLETON_PORTS.forEach(port -> beans.registerSingleton(port.getName(), mock(port)));
        contributors().forEach(type -> beans.registerSingleton(type.getName(), mock(type)));
        beans.registerSingleton("llm", llm());
        return beans;
    }

    private ActionExecutor llm() {
        var executor = mock(ActionExecutor.class);
        when(executor.supports("llm_call")).thenReturn(true);
        return executor;
    }

    private AgentAssemblyVerifier verifyAssembly(DefaultListableBeanFactory beans) {
        var verifier = new AgentAssemblyVerifier(beans, beans.getBeanProvider(AgentAssembly.class));
        verifier.afterSingletonsInstantiated();
        return verifier;
    }

    private ClassLoader descriptors(boolean installed, boolean duplicate) {
        return new ClassLoader(getClass().getClassLoader()) {
            @Override public Enumeration<URL> getResources(String name) throws IOException {
                if (!AgentAssemblyVerifier.DESCRIPTOR.equals(name)) return super.getResources(name);
                if (!installed) return Collections.emptyEnumeration();
                var original = Collections.list(super.getResources(name));
                assertThat(original).hasSize(1);
                return Collections.enumeration(duplicate ? List.of(original.get(0), original.get(0)) : original);
            }
        };
    }
}
