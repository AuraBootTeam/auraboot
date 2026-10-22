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

/** Test-only immutable admission for the two retained linked-order and foreign-directory Native methods. */
final class ProcurementLinkedForeignNativeAdmission {
    static final String MAIN_BASE="50924063538e4181cb601300f0c26c6bbfcce2f0";
    static final Set<String> PLUGINS=Set.of("com.auraboot.quote-engine");
    static final List<String> SOURCES=List.of(
        "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementPluginHostRealDbIT.java",
        "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementLinkedForeignNativeFixture.java",
        "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementLinkedForeignNativeAdmission.java",
        "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementLinkedForeignInitialization.java",
        "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementSupplierUiInitialization.java",
        "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementPublicSourceNativeFixture.java",
        "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementSourceUiFixtureProducerIT.java",
        "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementOrderUiFixtureProducerIT.java",
        "platform/src/test/java/com/auraboot/framework/notification/sourcebinding/ProductQuoteNativeInitialization.java");
    static final String MAIN_BASE_V2="d60a9f0acdfce618d4817a7a74da24d61f8b2e5d";
    static final String CRYPTO_SOURCE="platform/src/test/java/com/auraboot/framework/notification/sourcebinding/S06NativeEncryptionAdmission.java";
    static String mainBase(JsonNode input) {
        assertTrue(input.path("schemaVersion").isInt());
        int version=input.path("schemaVersion").intValue();
        assertTrue(version==1 || version==2);
        assertEquals("procurement-linked-foreign-native-admission-v"+version,input.path("kind").asText());
        return version==1?MAIN_BASE:MAIN_BASE_V2;
    }
    static List<String> sources(JsonNode input) {
        mainBase(input);
        if(input.path("schemaVersion").intValue()==1)return SOURCES;
        var sources=new java.util.ArrayList<>(SOURCES);sources.add(CRYPTO_SOURCE);return List.copyOf(sources);
    }
    static void validate(JsonNode input,Path attempt,Path app,FieldEncryptionService encryption,String matchingKey,
                         AuraPluginManager plugins,ObjectMapper json) throws Exception {
        validate(input,attempt,app,encryption,matchingKey,plugins,json,null);
    }
    static void validate(JsonNode input,Path attempt,Path app,FieldEncryptionService encryption,String matchingKey,
                         AuraPluginManager plugins,ObjectMapper json,String[] nativeProfiles) throws Exception {
        app=app.toRealPath();attempt=attempt.toRealPath();
        String mainBase=mainBase(input);
        assertEquals(mainBase,input.path("productionMainBase").asText());
        assertEquals(input.path("applicationCoreCommit").asText(),ProcurementPublicSourceNativeFixture.gitRead(app,"rev-parse","HEAD").strip());
        assertTrue(input.path("applicationCoreCommit").asText().matches("[a-f0-9]{40}"));
        assertEquals("",ProcurementPublicSourceNativeFixture.gitRead(app,"status","--porcelain").strip());
        assertEquals("",ProcurementPublicSourceNativeFixture.gitRead(app,"diff",mainBase,"HEAD","--","platform/src/main").strip());
        Path loaded=Path.of(ProcurementPluginHostRealDbIT.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        assertEquals(app.resolve("platform/build/classes/java/test").toRealPath(),loaded);
        assertEquals(Set.copyOf(sources(input)),keys(input.path("nativeSourceSha256")));
        for(String source:sources(input))assertEquals(input.path("nativeSourceSha256").path(source).asText(),sha(app.resolve(source)));
        validateClassRoster(input.path("classSha256"), loaded, sources(input));
        Path evidenceRoot=Path.of(input.path("workspaceRoot").asText()).resolve(".workspace/evidence/procurement-web-stack-20261010").toRealPath();
        assertTrue(attempt.startsWith(evidenceRoot));
        Path proofPath=Path.of(input.path("encryptionConfigProofPath").asText()).toRealPath();assertTrue(proofPath.startsWith(evidenceRoot));
        assertEquals(input.path("encryptionConfigProofSha256").asText(),sha(proofPath));JsonNode proof=json.readTree(proofPath.toFile());
        assertEquals(input.path("sourceSet").path("oss").asText(),proof.path("sourceCommit").asText());
        assertEquals(sha(app.resolve("platform/src/main/java/com/auraboot/framework/common/crypto/FieldEncryptionService.java")),proof.path("fieldEncryptionServiceSha256").asText());
        assertEquals(sha(app.resolve("platform/platform-app/src/main/resources/application.yml")),proof.path("applicationYmlSha256").asText());
        String mode;
        if(input.path("schemaVersion").intValue()==1) {
            mode=ProcurementOrderUiFixtureProducerIT.validateEncryptionMode(proof,matchingKey);
        } else {
            var env=System.getenv();
            validatePrivateCryptoBindings(input,proof,app,proofPath,matchingKey,env);
            assertNotNull(nativeProfiles,"Native actual profiles must come from Spring Environment");
            var admitted=com.auraboot.framework.notification.sourcebinding.S06NativeEncryptionAdmission.admit(env,json,encryption,
                    ProcurementPluginHostRealDbIT.class,nativeProfiles);
            assertEquals(proofPath.toString(),admitted.get("proofPath"));
            assertEquals(input.path("encryptionConfigProofSha256").asText(),admitted.get("proofSha256"));
            mode=(String)admitted.get("mode");
        }
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
        assertEquals(PLUGINS,keys(input.path("fixturePluginArtifacts")));
        for(String id:PLUGINS){
            var plugin=plugins.getPlugin(id);assertNotNull(plugin);assertEquals(PluginState.STARTED,plugin.getPluginState());
            Path path=plugin.getPluginPath().toRealPath();assertEquals(directory,path.getParent());
            JsonNode artifact=input.path("fixturePluginArtifacts").path(id);assertEquals(path.toString(),artifact.path("path").asText());
            String digest=sha(path);assertEquals(digest,artifact.path("sha256").asText());
            var locked=StreamSupport.stream(lock.path("jars").spliterator(),false).filter(n->path.getFileName().toString().equals(n.path("file").asText())).toList();
            assertEquals(1,locked.size());assertEquals(digest,locked.getFirst().path("sha256").asText());
        }
    }
    static void validatePrivateCryptoBindings(JsonNode input,JsonNode proof,Path app,Path proofPath,
                                              String matchingKey,java.util.Map<String,String> environment) throws Exception {
        assertEquals("private-additional-properties",proof.path("keySource").asText());
        assertEquals("encrypted",input.path("encryptionMode").asText());
        for(String name:List.of("S06_NATIVE_APPLICATION_ROOT","S06_NATIVE_ENCRYPTION_PROOF_PATH",
                "S06_NATIVE_ENCRYPTION_PROOF_SHA256","PROCUREMENT_HOST_FIELD_ENCRYPTION_KEY"))
            assertNotNull(environment.get(name),"Matching managed alias must be explicit: "+name);
        assertEquals(app.toRealPath(),Path.of(environment.get("S06_NATIVE_APPLICATION_ROOT")).toRealPath());
        assertEquals(proofPath.toRealPath(),Path.of(environment.get("S06_NATIVE_ENCRYPTION_PROOF_PATH")).toRealPath());
        assertEquals(input.path("encryptionConfigProofSha256").asText(),environment.get("S06_NATIVE_ENCRYPTION_PROOF_SHA256"));
        assertNotNull(matchingKey,"FOREIGN_MATCHING_KEY_REQUIRED");
        assertTrue(java.security.MessageDigest.isEqual(matchingKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                environment.get("PROCUREMENT_HOST_FIELD_ENCRYPTION_KEY").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "FOREIGN_HOST_KEY_ALIAS_DRIFT");
        assertTrue(java.security.MessageDigest.isEqual(matchingKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                com.auraboot.framework.notification.sourcebinding.S06NativeEncryptionAdmission.requiredKey(environment)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),"FOREIGN_S06_KEY_ALIAS_DRIFT");
    }
    static void validateClassRoster(JsonNode declared,Path classes) throws Exception {
        validateClassRoster(declared,classes,SOURCES);
    }
    static void validateClassRoster(JsonNode declared,Path classes,List<String> sources) throws Exception {
        assertTrue(declared.isObject() && !declared.isEmpty());
        var actual=new java.util.LinkedHashMap<String,String>();
        var families=new java.util.ArrayList<String>();
        for(String source:sources)families.add(source.substring("platform/src/test/java/".length(),source.length()-".java".length()));
        families.add("com/auraboot/framework/application/TestApplication");
        for(String family:families) {
            Path main=classes.resolve(family+".class");assertTrue(Files.isRegularFile(main) && !Files.isSymbolicLink(main));
            var paths=new java.util.ArrayList<Path>();paths.add(main);
            try(var files=Files.list(main.getParent())) {
                paths.addAll(files.filter(p->p.getFileName().toString().startsWith(main.getFileName().toString().replace(".class", "$"))
                        && p.getFileName().toString().endsWith(".class")).toList());
            }
            for(Path file:paths) {
                assertFalse(Files.isSymbolicLink(file));Path real=file.toRealPath();assertTrue(real.startsWith(classes.toRealPath()));
                assertNull(actual.put(real.toString(),sha(real)));
            }
        }
        assertEquals(actual, new ObjectMapper().convertValue(declared,java.util.Map.class),"Exact complete compiled fixture families required");
    }
    static Set<String> keys(JsonNode node){assertTrue(node.isObject());var keys=new java.util.HashSet<String>();node.fieldNames().forEachRemaining(keys::add);return Set.copyOf(keys);}
    private static String sha(Path path) throws Exception{return ProcurementPublicSourceNativeFixture.sha(path);}
}
