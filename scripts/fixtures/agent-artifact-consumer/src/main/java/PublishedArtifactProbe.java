import java.util.Collections;
import java.util.List;

/** Executes against published coordinates only; no producer project or test outputs are on this classpath. */
public final class PublishedArtifactProbe {
    private static int checks;
    private static final String ROOT = "com.auraboot.framework.";
    private static final List<String> INTENT_TYPES = List.of(
            "controller.IntentController", "dto.IntentAnalysisRequest", "dto.IntentAnalysisResult",
            "dto.PluginDeployRequest", "dto.PluginDeployResult", "dto.PluginGenerateRequest",
            "dto.PluginGenerateResult", "service.DefaultLlmClient", "service.IntentAnalyzerService",
            "service.LlmClient", "service.PluginDeployService", "service.PluginGeneratorService");

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new AssertionError("Supply one fixed probe mode");
        String mode = args[0];
        if (!List.of("base", "agent", "full", "coreFixtures", "agentFixtures", "testApi").contains(mode)) {
            throw new AssertionError("Unknown probe mode: " + mode);
        }
        boolean agent = List.of("agent", "full", "agentFixtures").contains(mode);
        unique(ROOT + "common.util.UniqueIdGenerator", 1);
        unique(ROOT + "meta.ai.AiFieldGenerator", 1);
        unique(ROOT + "agent.provider.LlmProviderFactory", agent ? 1 : 0);
        unique("io.modelcontextprotocol.spec.McpSchema", agent ? 1 : 0);
        for (String type : INTENT_TYPES) unique(ROOT + "intent." + type, agent ? 1 : 0);
        unique(ROOT + "application.MetaApplication", mode.equals("full") ? 1 : 0);
        unique(ROOT + "application.PlatformApplication", mode.equals("full") ? 1 : 0);
        unique(ROOT + "test.util.OwnedTestDatabase", mode.equals("coreFixtures") ? 1 : 0);
        unique(ROOT + "agent.util.LiveLlmSeeder", mode.equals("agentFixtures") ? 1 : 0);
        for (String controller : List.of("TestSeedController", "TestFixtureController", "TestImBroadcastController")) {
            unique(ROOT + "test.controller." + controller, mode.equals("testApi") ? 1 : 0);
        }
        if (mode.equals("full")) {
            Class.forName(ROOT + "application.MetaApplication").getMethod("main", String[].class);
            Class.forName(ROOT + "application.PlatformApplication").getMethod("main", String[].class);
            var client = Class.forName(ROOT + "intent.service.LlmClient");
            client.getMethod("chat", String.class);
            client.getMethod("chat", String.class, Class.forName(ROOT + "intent.service.LlmClient$ChatOptions"));
            checks += 3;
        }
        System.out.println("PUBLISHED_ARTIFACT_PROBE_PASS mode=" + mode + " checks=" + checks);
    }

    private static void unique(String type, int expected) throws Exception {
        var loader = Thread.currentThread().getContextClassLoader();
        var origins = Collections.list(loader.getResources(type.replace('.', '/') + ".class"));
        if (origins.size() != expected) {
            throw new AssertionError(type + ": expected " + expected + " published owners, found " + origins);
        }
        if (expected == 1) Class.forName(type, false, loader);
        checks++;
    }
}
