package com.auraboot.framework.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture constraints enforced via ArchUnit.
 * These tests prevent architectural drift by codifying layer dependency rules.
 *
 * Uses FreezingArchRule for rules with existing violations:
 * - First run records violations in src/test/resources/archunit_store/
 * - Subsequent runs only fail on NEW violations
 * - Removing a violation from the codebase auto-removes it from the store
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.auraboot.framework");
    }

    @Test
    @DisplayName("Rule 1: No class should depend on controller layer")
    void noClassShouldDependOnControllers() {
        noClasses()
                .that().resideOutsideOfPackage("..controller..")
                .should().dependOnClassesThat().resideInAnyPackage("..controller..")
                .because("Controllers are entry points — no other layer should depend on them")
                .check(classes);
    }

    @Test
    @DisplayName("Rule 2: Controllers must not directly access mapper/dao layer (frozen — new violations blocked)")
    void controllersShouldNotAccessMappers() {
        FreezingArchRule.freeze(
                noClasses()
                        .that().resideInAnyPackage("..controller..")
                        .should().dependOnClassesThat().resideInAnyPackage("..mapper..", "..dao..")
                        .because("Controllers must go through service layer, not directly access data layer")
        ).check(classes);
    }

    @Test
    @DisplayName("Rule 3: Service layer must not depend on controller layer")
    void servicesShouldNotDependOnControllers() {
        noClasses()
                .that().resideInAnyPackage("..service..", "..service.impl..")
                .should().dependOnClassesThat().resideInAnyPackage("..controller..")
                .because("Service layer must not have upward dependencies to controllers")
                .check(classes);
    }

    @Test
    @DisplayName("Rule 4: Entity/DAO layer must not depend on service or controller")
    void entitiesShouldNotDependOnUpperLayers() {
        noClasses()
                .that().resideInAnyPackage("..entity..", "..dao.entity..")
                .should().dependOnClassesThat().resideInAnyPackage("..service..", "..service.impl..", "..controller..")
                .because("Entities are data carriers — they must not know about business or presentation logic")
                .check(classes);
    }

    @Test
    @DisplayName("Rule 5: Framework core must not depend on module-specific packages (frozen — new violations blocked)")
    void frameworkCoreShouldNotDependOnModules() {
        FreezingArchRule.freeze(
                noClasses()
                        .that().resideInAnyPackage(
                                "com.auraboot.framework.application..",
                                "com.auraboot.framework.meta.."
                        )
                        .should().dependOnClassesThat().resideInAnyPackage(
                                "com.auraboot.framework.crm..",
                                "com.auraboot.framework.bpm..",
                                "com.auraboot.framework.finance..",
                                "com.auraboot.framework.aps..",
                                "com.auraboot.framework.mrp.."
                        )
                        .because("Framework core must remain module-agnostic")
        ).check(classes);
    }
}
