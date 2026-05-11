package com.auraboot.framework.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Agent runtime architecture constraints")
class AgentRuntimeArchitectureTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java/com/auraboot/framework");
    private static final Pattern GENERIC_EXECUTE_TOOL_METHOD =
            Pattern.compile("\\bexecuteTool\\s*\\(");

    @Test
    @DisplayName("only ToolLoopService may call SkillToolExecutor dispatch or confirm")
    void onlyToolLoopServiceMayCallSkillToolExecutor() throws Exception {
        List<Path> offenders = productionJavaFiles()
                .filter(path -> !path.endsWith("agent/service/ToolLoopService.java"))
                .filter(path -> containsAny(path,
                        "skillToolExecutor.dispatch(",
                        "skillToolExecutor.confirm("))
                .toList();

        assertThat(offenders)
                .as("AuraBot skill execution must stay inside ToolLoopService")
                .isEmpty();
    }

    @Test
    @DisplayName("tool discovery and execution ports must not expose generic executeTool fallback")
    void portsDoNotExposeGenericExecuteToolFallback() throws Exception {
        List<Path> offenders = List.of(
                        MAIN_SOURCES.resolve("agent/port/ToolDiscoveryPort.java"))
                .stream()
                .filter(path -> contains(path, GENERIC_EXECUTE_TOOL_METHOD))
                .toList();

        assertThat(offenders)
                .as("Port contracts must not reintroduce a generic executeTool runtime path")
                .isEmpty();
    }

    @Test
    @DisplayName("legacy ToolExecutionPort must not be reintroduced")
    void toolExecutionPortIsRemoved() {
        assertThat(MAIN_SOURCES.resolve("agent/port/ToolExecutionPort.java"))
                .as("Tool execution must stay on ToolLoopService; do not reintroduce an execution port adapter")
                .doesNotExist();
    }

    @Test
    @DisplayName("entry adapters must not call package-local DSL shortcut execution")
    void entryAdaptersMustNotCallDslShortcutExecution() throws Exception {
        List<Path> offenders = productionJavaFiles()
                .filter(path -> !path.endsWith("agent/service/ToolLoopService.java"))
                .filter(path -> !path.endsWith("agent/service/SkillEngine.java"))
                .filter(path -> containsAny(path,
                        ".executeDslCommand(",
                        ".executeDslQuery(",
                        " executeDslCommand(",
                        " executeDslQuery("))
                .toList();

        assertThat(offenders)
                .as("Entry adapters must use executeToolCall/confirmAuraBotSkill so auth/effects/actions/contracts stay unified")
                .isEmpty();
    }

    @Test
    @DisplayName("agent runtime must not return deterministic fake tool execution stubs")
    void agentRuntimeDoesNotReturnFakeToolExecutionStubs() throws Exception {
        List<Path> offenders = productionJavaFiles()
                .filter(path -> containsAny(path, "Tool executed:", "Tool executed: "))
                .toList();

        assertThat(offenders)
                .as("Tool calls must execute through ToolLoopService, not return fake deterministic stubs")
                .isEmpty();
    }

    @Test
    @DisplayName("chat path must not call removed discovery or execution executeTool methods")
    void chatPathDoesNotCallRemovedExecuteToolMethods() throws Exception {
        List<Path> offenders = productionJavaFiles()
                .filter(path -> containsAny(path,
                        "toolDiscoveryPort.executeTool(",
                        "ToolDiscoveryPort.executeTool(",
                        "ToolExecutionPort.executeTool("))
                .toList();

        assertThat(offenders)
                .as("Chat and agent paths must use ToolLoopService instead of removed executeTool shortcuts")
                .isEmpty();
    }

    private static Stream<Path> productionJavaFiles() throws IOException {
        return Files.walk(MAIN_SOURCES)
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".java"))
                .filter(AgentRuntimeArchitectureTest::isAgentRuntimePath);
    }

    private static boolean isAgentRuntimePath(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.contains("/agent/")
                || normalized.contains("/aurabot/")
                || normalized.contains("/conversation/");
    }

    private static boolean containsAny(Path path, String... needles) {
        try {
            String text = Files.readString(path);
            for (String needle : needles) {
                if (text.contains(needle)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }

    private static boolean contains(Path path, Pattern pattern) {
        try {
            return pattern.matcher(Files.readString(path)).find();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }
}
