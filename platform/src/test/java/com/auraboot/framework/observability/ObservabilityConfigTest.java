package com.auraboot.framework.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.annotation.Observed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservabilityConfigTest {

    @Test
    void registersAspectThatActivatesObservedAnnotations() {
        verifyObservedSuccess();
    }

    @Test
    void recordsObservedFailureAndRestoresTheScope() {
        verifyObservedFailure();
    }

    @Test
    void startsWithAllureWeavingWithoutReweavingTheSpringObservationAspect(@TempDir Path directory)
            throws Exception {
        Path output = directory.resolve("observation-weaving.txt");
        List<String> arguments = new ArrayList<>();
        arguments.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        List<String> weavers = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("aspectjweaver-"))
                .toList();
        assertThat(weavers).hasSize(1);
        arguments.addAll(weavers);
        // The child has its own contract and must not overwrite the parent JVM's coverage.
        arguments.add("-Djacoco-agent.output=none");
        arguments.add("-cp");
        arguments.add(currentTestClasspath());
        arguments.add(ObservabilityConfigTest.class.getName());
        Process child = new ProcessBuilder(arguments).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        try {
            assertThat(child.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS))
                    .as("fresh JVM completed; output: %s", output).isTrue();
            String text = Files.readString(output);
            System.out.print(text);
            assertThat(child.exitValue()).as(text).isZero();
            assertThat(text).contains("OBSERVATION_CONTRACT_PASSED")
                    .doesNotContain("must be defined to the weaver")
                    .doesNotContain("error aspect");
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static String currentTestClasspath() throws Exception {
        LinkedHashSet<String> paths = new LinkedHashSet<>(
                Arrays.asList(System.getProperty("java.class.path").split(File.pathSeparator)));
        for (ClassLoader loader = ObservabilityConfigTest.class.getClassLoader();
                loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    if ("file".equals(url.getProtocol())) paths.add(Path.of(url.toURI()).toString());
                }
            }
        }
        assertThat(paths).as("actual Gradle test runtime classpath").contains(
                Path.of(ObservabilityConfigTest.class.getProtectionDomain().getCodeSource()
                        .getLocation().toURI()).toString());
        return String.join(File.pathSeparator, paths);
    }

    public static void main(String[] arguments) {
        verifyObservedSuccess();
        verifyObservedFailure();
        System.out.println("OBSERVATION_CONTRACT_PASSED");
    }

    private static ObservedService observedService(ObservationRegistry observations, SimpleMeterRegistry meters) {
        ObservabilityConfig config = new ObservabilityConfig(
                new ApiMetricsInterceptor(new ObservabilityMetrics(meters)));
        AspectJProxyFactory factory = new AspectJProxyFactory(new ObservedService(observations));
        factory.addAspect(config.observedAspect(observations));
        return factory.getProxy();
    }

    private static void verifyObservedSuccess() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            ObservationRegistry observations = ObservationRegistry.create();
            observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
            ObservedService proxy = observedService(observations, meters);
            assertThat(proxy.success()).isEqualTo("observed.success");
            assertThat(meters.get("observed.success").tag("error", "none").timer().count()).isEqualTo(1);
            assertThat(observations.getCurrentObservation()).isNull();
        } finally {
            meters.close();
        }
    }

    private static void verifyObservedFailure() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            ObservationRegistry observations = ObservationRegistry.create();
            observations.observationConfig().observationHandler(new DefaultMeterObservationHandler(meters));
            ObservedService proxy = observedService(observations, meters);
            assertThatThrownBy(proxy::failure).isInstanceOf(IllegalStateException.class).hasMessage("expected failure");
            assertThat(meters.get("observed.failure").tag("error", "IllegalStateException").timer().count())
                    .isEqualTo(1);
            assertThat(observations.getCurrentObservation()).isNull();
        } finally {
            meters.close();
        }
    }

    static class ObservedService {
        private final ObservationRegistry observations;

        ObservedService(ObservationRegistry observations) {
            this.observations = observations;
        }

        @Observed(name = "observed.success")
        public String success() {
            return observations.getCurrentObservation().getContext().getName();
        }

        @Observed(name = "observed.failure")
        public void failure() {
            assertThat(observations.getCurrentObservation()).isNotNull();
            throw new IllegalStateException("expected failure");
        }
    }
}
