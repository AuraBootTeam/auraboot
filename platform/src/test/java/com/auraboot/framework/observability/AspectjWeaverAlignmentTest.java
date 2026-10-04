package com.auraboot.framework.observability;

import io.qameta.allure.Allure;
import io.qameta.allure.Step;
import org.aspectj.weaver.ResolvedType;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AspectjWeaverAlignmentTest {

    @Test
    void attachedWeaverMatchesTheRuntimeWeaverArtifact() throws Exception {
        List<String> weavers = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("aspectjweaver-"))
                .toList();
        assertThat(weavers).hasSize(1);
        Path attached = Path.of(weavers.getFirst().substring("-javaagent:".length()).split("=", 2)[0]);
        Path runtime = Path.of(ResolvedType.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertThat(attached.getFileName()).isEqualTo(runtime.getFileName());
        assertThat(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(attached)))
                .isEqualTo(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(runtime)));
    }

    @Test
    void allureStepWeavingRemainsActiveAndRestoresTheParentContext() {
        String parent = Allure.getLifecycle().getCurrentTestCaseOrStep().orElseThrow();
        String step = wovenStepIdentity();
        assertThat(step).isNotBlank().isNotEqualTo(parent);
        assertThat(Allure.getLifecycle().getCurrentTestCaseOrStep()).contains(parent);
    }

    @Step("Verify active Allure step weaving")
    private String wovenStepIdentity() {
        return Allure.getLifecycle().getCurrentTestCaseOrStep().orElseThrow();
    }
}
