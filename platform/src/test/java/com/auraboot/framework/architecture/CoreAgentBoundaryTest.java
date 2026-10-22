package com.auraboot.framework.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/** Checks every actual core class, independent of agent/app compilation and test outputs. */
class CoreAgentBoundaryTest {
    @TempDir
    Path directory;
    @Test
    void platformConsumersDoNotDependOnAgentImplementations() throws Exception {
        try (var jar = new JarFile(System.getProperty("auraboot.core.jar"))) {
            var classes = new ClassFileImporter().importJar(jar);
            assertThat(classes).isNotEmpty();
            boundary().check(classes);
        }
    }

    private static com.tngtech.archunit.lang.ArchRule boundary() {
        return noClasses().should().dependOnClassesThat().resideInAnyPackage(
                "com.auraboot.framework.agent..", "com.auraboot.framework.agentchat..",
                "com.auraboot.framework.conversation..", "com.auraboot.framework.aurabot..",
                "com.auraboot.framework.rag..", "com.auraboot.framework.intent..", "com.auraboot.framework.aisearch..")
                .because("all core classes own contracts and optional agent adapters implement them");
    }

    @Test
    void boundaryRejectsAnImplementationTypeInAFieldSignature() throws Exception {
        // Compile a deliberately invalid bytecode dependency without putting agent on core's classpath.
        Path provider = directory.resolve("LlmProvider.java");
        Path consumer = directory.resolve("AgentBoundaryMutationFixture.java");
        Files.writeString(provider, "package com.auraboot.framework.agent.provider; public interface LlmProvider {}");
        Files.writeString(consumer, """
                package com.auraboot.framework.meta.ai;
                public class AgentBoundaryMutationFixture {
                    public com.auraboot.framework.agent.provider.LlmProvider provider;
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).isNotNull();
        assertThat(compiler.run(null, null, null, "-d", directory.toString(),
                provider.toString(), consumer.toString())).isZero();
        var fixture = new ClassFileImporter().importPath(directory);
        assertThat(boundary().evaluate(fixture).hasViolation()).isTrue();
    }
}
