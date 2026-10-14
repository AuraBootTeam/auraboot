package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.pf4j.PluginState;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only immutable admission for the four trusted receipt Native methods. */
final class ProcurementInventoryNativeAdmission {
    static final String MAIN_BASE="1210c6e2ce89ed2ec28b9fc2120bd86051eb043e";
    static final Set<String> PLUGINS=Set.of("com.auraboot.inventory","com.auraboot.procurement");
    static final List<String> SOURCES=List.of(
        "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementInventoryReceiptRealDbIT.java",
        "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementInventoryNativeAdmission.java",
        "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementPublicSourceNativeFixture.java",
        "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementOrderUiFixtureProducerIT.java",
        "platform/src/test/java/com/auraboot/framework/notification/sourcebinding/ProductQuoteNativeInitialization.java");
    static void validate(JsonNode input,Path attempt,Path app,FieldEncryptionService encryption,String matchingKey,
                         AuraPluginManager plugins,ObjectMapper json) throws Exception {
        app=app.toRealPath();attempt=attempt.toRealPath();
        assertEquals("procurement-inventory-native-admission-v1",input.path("kind").asText());
        assertEquals(1,input.path("schemaVersion").asInt(-1));
        assertEquals(MAIN_BASE,input.path("productionMainBase").asText());
        assertEquals(input.path("applicationCoreCommit").asText(),ProcurementPublicSourceNativeFixture.gitRead(app,"rev-parse","HEAD").strip());
        assertEquals(input.path("sourceSet").path("oss").asText(),input.path("applicationCoreCommit").asText());
        assertEquals("",ProcurementPublicSourceNativeFixture.gitRead(app,"status","--porcelain").strip());
        assertEquals("",ProcurementPublicSourceNativeFixture.gitRead(app,"diff",MAIN_BASE,"HEAD","--","platform/src/main").strip());
        Path loaded=Path.of(ProcurementInventoryReceiptRealDbIT.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        assertEquals(app.resolve("platform/build/classes/java/test").toRealPath(),loaded);
        assertEquals(Set.copyOf(SOURCES),keys(input.path("nativeSourceSha256")));
        for(String source:SOURCES)assertEquals(input.path("nativeSourceSha256").path(source).asText(),sha(app.resolve(source)));
        JsonNode classes=input.path("classSha256");assertTrue(classes.isObject() && !classes.isEmpty());
        for(var entry:classes.properties()){
            Path file=Path.of(entry.getKey()).toRealPath();assertTrue(file.startsWith(loaded));
            assertEquals(entry.getValue().asText(),sha(file));
        }
        Path evidenceRoot=Path.of(input.path("workspaceRoot").asText()).resolve(".workspace/evidence/procurement-web-stack-20261010").toRealPath();
        assertTrue(attempt.startsWith(evidenceRoot));
        Path proofPath=Path.of(input.path("encryptionConfigProofPath").asText()).toRealPath();assertTrue(proofPath.startsWith(evidenceRoot));
        assertEquals(input.path("encryptionConfigProofSha256").asText(),sha(proofPath));JsonNode proof=json.readTree(proofPath.toFile());
        assertEquals(input.path("sourceSet").path("oss").asText(),proof.path("sourceCommit").asText());
        assertEquals(sha(app.resolve("platform/src/main/java/com/auraboot/framework/common/crypto/FieldEncryptionService.java")),proof.path("fieldEncryptionServiceSha256").asText());
        assertEquals(sha(app.resolve("platform/src/main/resources/application.yml")),proof.path("applicationYmlSha256").asText());
        String mode=ProcurementOrderUiFixtureProducerIT.validateEncryptionMode(proof,matchingKey);
        assertEquals(input.path("encryptionMode").asText(),mode);
        for(String stem:List.of("runtimeAdmission","runtimeEnvironmentEvidence","runtimeProfileEvidence")){
            Path file=Path.of(proof.path(stem+"Path").asText()).toRealPath();assertTrue(file.startsWith(evidenceRoot));
            assertEquals(proof.path(stem+"Sha256").asText(),sha(file));
        }
        JsonNode runtime=json.readTree(Path.of(proof.path("runtimeAdmissionPath").asText()).toFile());
        assertEquals("procurement-web-stack-20261010",runtime.path("runtime").asText());assertEquals("ok",runtime.path("verdict").asText());
        assertEquals(proof.path("runtimeMode").asText(),runtime.path("metadata").path("mode").asText());
        var core=StreamSupport.stream(runtime.path("sources").spliterator(),false).filter(n->"oss".equals(n.path("key").asText())).toList();
        assertEquals(1,core.size());assertEquals(proof.path("sourceCommit").asText(),core.getFirst().path("actual").path("commit").asText());
        JsonNode env=json.readTree(Path.of(proof.path("runtimeEnvironmentEvidencePath").asText()).toFile());
        assertEquals(proof.path("environmentKeyState").asText(),env.path("fieldEncryptionKeyState").asText());
        JsonNode profile=json.readTree(Path.of(proof.path("runtimeProfileEvidencePath").asText()).toFile());
        assertEquals(proof.path("actualSpringProfiles").asText(),profile.path("actualSpringProfiles").asText());
        String probe="SyntheticNativeReceiptCryptoProbe",encrypted=encryption.encrypt(probe);
        assertEquals(probe,encryption.decrypt(encrypted));
        if("dev-passthrough".equals(mode))assertEquals(probe,encrypted);else assertTrue(encrypted.startsWith("ENC:"));
        Path directory=Path.of(input.path("pluginDir").asText()).toRealPath();
        Path lockPath=Path.of(input.path("pluginLockPath").asText()).toRealPath();
        assertEquals(input.path("pluginLockSha256").asText(),sha(lockPath));JsonNode lock=json.readTree(lockPath.toFile());
        assertEquals(PLUGINS,keys(input.path("receiptPluginArtifacts")));
        for(String id:PLUGINS){
            var plugin=plugins.getPlugin(id);assertNotNull(plugin);assertEquals(PluginState.STARTED,plugin.getPluginState());
            Path path=plugin.getPluginPath().toRealPath();assertEquals(directory,path.getParent());
            JsonNode artifact=input.path("receiptPluginArtifacts").path(id);assertEquals(path.toString(),artifact.path("path").asText());
            String digest=sha(path);assertEquals(digest,artifact.path("sha256").asText());
            var locked=StreamSupport.stream(lock.path("jars").spliterator(),false).filter(n->path.getFileName().toString().equals(n.path("file").asText())).toList();
            assertEquals(1,locked.size());assertEquals(digest,locked.getFirst().path("sha256").asText());
        }
    }
    static Set<String> keys(JsonNode node){assertTrue(node.isObject());var keys=new java.util.HashSet<String>();node.fieldNames().forEachRemaining(keys::add);return Set.copyOf(keys);}
    private static String sha(Path path) throws Exception{return ProcurementPublicSourceNativeFixture.sha(path);}
}
