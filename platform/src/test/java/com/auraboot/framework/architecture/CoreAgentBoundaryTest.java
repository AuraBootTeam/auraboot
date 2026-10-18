package com.auraboot.framework.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Strict boundaries already migrated in S1; other consumer domains remain tracked separately. */
class CoreAgentBoundaryTest {
    @Test
    void platformConsumersDoNotDependOnAgentImplementations() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.auraboot.framework.meta", "com.auraboot.framework.event",
                        "com.auraboot.framework.application", "com.auraboot.framework.observability");
        boundary().check(classes);
    }

    private static com.tngtech.archunit.lang.ArchRule boundary() {
        return noClasses().that().resideInAnyPackage(
                        "com.auraboot.framework.meta..", "com.auraboot.framework.event..",
                        "com.auraboot.framework.application..", "com.auraboot.framework.observability..")
                .should().dependOnClassesThat().resideInAnyPackage("com.auraboot.framework.agent..")
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
