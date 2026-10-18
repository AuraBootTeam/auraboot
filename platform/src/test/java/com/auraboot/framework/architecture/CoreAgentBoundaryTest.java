package com.auraboot.framework.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Consumer contracts stay in core; AI implementations depend on these contracts. */
class CoreAgentBoundaryTest {
    @Test
    void platformConsumersDoNotDependOnAgentImplementations() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.auraboot.framework.meta", "com.auraboot.framework.event",
                        "com.auraboot.framework.application", "com.auraboot.framework.observability",
                        "com.auraboot.framework.plugin", "com.auraboot.framework.im",
                        "com.auraboot.framework.automation", "com.auraboot.framework.behavior",
                        "com.auraboot.framework.i18n", "com.auraboot.framework.faq", "com.auraboot.framework.tenant");
        boundary().check(classes);
    }

    private static com.tngtech.archunit.lang.ArchRule boundary() {
        return noClasses().that().resideInAnyPackage(
                        "com.auraboot.framework.meta..", "com.auraboot.framework.event..",
                        "com.auraboot.framework.application..", "com.auraboot.framework.observability..",
                        "com.auraboot.framework.plugin..", "com.auraboot.framework.im..",
                        "com.auraboot.framework.automation..", "com.auraboot.framework.behavior..",
                        "com.auraboot.framework.i18n..", "com.auraboot.framework.faq..", "com.auraboot.framework.tenant..")
                .should().dependOnClassesThat().resideInAnyPackage("com.auraboot.framework.agent..", "com.auraboot.framework.agentchat..",
                        "com.auraboot.framework.conversation..", "com.auraboot.framework.aurabot..",
                        "com.auraboot.framework.rag..", "com.auraboot.framework.intent..", "com.auraboot.framework.aisearch..")
                .because("platform consumers own contracts and agent adapters implement them")
                ;
    }

    @Test
    void boundaryRejectsAnImplementationTypeInAFieldSignature() {
        var fixture = new ClassFileImporter().importClasses(
                com.auraboot.framework.meta.ai.AgentBoundaryMutationFixture.class);
        org.assertj.core.api.Assertions.assertThat(boundary().evaluate(fixture).hasViolation()).isTrue();
    }
}
