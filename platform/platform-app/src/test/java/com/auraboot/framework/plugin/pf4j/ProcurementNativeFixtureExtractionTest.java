package com.auraboot.framework.plugin.pf4j;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import static org.assertj.core.api.Assertions.assertThat;

/** Structural inheritance admission only; real Source five/order three require their own native run. */
class ProcurementNativeFixtureExtractionTest {
    @Test void sharedFixtureHasNoTestMethodsOrSuiteActivation() {
        assertThat(Arrays.stream(ProcurementPublicSourceNativeFixture.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Test.class))).isEmpty();
        assertThat(ProcurementPublicSourceNativeFixture.class.getAnnotation(SpringBootTest.class)).isNull();
        assertThat(Arrays.stream(ProcurementPublicSourceNativeFixture.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(BeforeEach.class))).hasSize(1);
        assertThat(Arrays.stream(ProcurementPublicSourceNativeFixture.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(AfterEach.class))).hasSize(1);
    }

    @Test void originalSourceRosterAndExplicitConfigurationArePreserved() {
        Set<String> names = Arrays.stream(ProcurementPublicSourceCommandRealDbIT.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Test.class)).map(method -> method.getName())
                .collect(Collectors.toSet());
        assertThat(names).containsExactlyInAnyOrder(
                "publicConfiguredReadCreatesThenReplaysWithoutDuplicatePgFacts",
                "publicRoleAndPayloadCannotGrantIntegrationOrSecretCapabilities",
                "publicMissingPolicyAndLaterUnknownCodeRejectWholeReadWithoutWrites",
                "publicCriticalChangeInvalidatesWithPersistedSystemHistoryAndRetainsFormalOffer",
                "publicBatchRollsBackAnActuallyWrittenFirstSourceWhenLaterAllocatedSourceChanges",
                "publicConfiguredOrderCreatesImmutableSourceLinkedFactsAndReplaysWithoutAnotherSave",
                "publicUnknownSaveKeepsIndependentCheckpointUntilMarkerLookupWithoutResending",
                "publicOrderTargetCannotCrossIntoAnotherFormallyImportedTenant");
        assertThat(ProcurementPublicSourceCommandRealDbIT.class.getSuperclass())
                .isEqualTo(ProcurementPublicSourceNativeFixture.class);
        assertThat(ProcurementPublicSourceCommandRealDbIT.class.getAnnotation(Import.class).value())
                .containsExactly(ProcurementPublicSourceNativeFixture.IsolatedScheduling.class);
        assertThat(ProcurementPublicSourceCommandRealDbIT.class.getAnnotation(SpringBootTest.class).properties())
                .contains("aura.plugins.dir=${PROCUREMENT_SOURCE_PLUGIN_DIR}");
    }

    @Test void anIndependentProducerCannotInheritTheOriginalEightTests() {
        for (Class<?> type = ProducerStructureOnly.class; type != Object.class; type = type.getSuperclass()) {
            assertThat(Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(Test.class))).isEmpty();
        }
        assertThat(ProducerStructureOnly.class.getAnnotation(SpringBootTest.class)).isNull();
    }

    private static class ProducerStructureOnly extends ProcurementPublicSourceNativeFixture { }
}
