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
    static final String MAIN_BASE="39c92a8e2b15499f236f55f6aa0e804bfba67390";
    static final Set<String> PLUGINS=Set.of("com.auraboot.inventory","com.auraboot.procurement");
    static final List<String> SOURCES=List.of(
        "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementInventoryReceiptRealDbIT.java",
        "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementInventoryNativeAdmission.java",
        "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementPublicSourceNativeFixture.java",
        "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementOrderUiFixtureProducerIT.java",
        "platform/src/test/java/com/auraboot/framework/plugin/pf4j/ProcurementSourceUiFixtureProducerIT.java",
        "platform/src/test/java/com/auraboot/framework/notification/sourcebinding/ProductQuoteNativeInitialization.java",
        "platform/src/test/java/com/auraboot/framework/notification/sourcebinding/S06NativeEncryptionAdmission.java");
    static void validate(JsonNode input,Path attempt,Path app,FieldEncryptionService encryption,String matchingKey,
                         AuraPluginManager plugins,ObjectMapper json,String[] actualNativeProfiles) throws Exception {
        app=app.toRealPath();attempt=attempt.toRealPath();
        assertEquals("procurement-inventory-native-admission-v1",input.path("kind").asText());
        assertEquals(1,input.path("schemaVersion").asInt(-1));
        assertEquals(MAIN_BASE,input.path("productionMainBase").asText());
        validateApplicationIdentity(input,ProcurementPublicSourceNativeFixture.gitRead(app,"rev-parse","HEAD").strip(),
                ProcurementPublicSourceNativeFixture.gitRead(app,"status","--porcelain").strip(),
                ProcurementPublicSourceNativeFixture.gitRead(app,"diff",MAIN_BASE,"HEAD","--","platform/src/main").strip());
        Path runtimeRoot=Path.of(input.path("verifiedSourceIdentities").path("oss").path("root").asText()).toRealPath();
        assertEquals(input.path("runtimeCoreCommit").asText(),ProcurementPublicSourceNativeFixture.gitRead(runtimeRoot,"rev-parse","HEAD").strip());
        assertEquals("",ProcurementPublicSourceNativeFixture.gitRead(runtimeRoot,"status","--porcelain").strip());
        assertEquals("",ProcurementPublicSourceNativeFixture.gitRead(runtimeRoot,"diff",MAIN_BASE,"HEAD","--","platform/src/main").strip());
        Path loaded=Path.of(ProcurementInventoryReceiptRealDbIT.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        assertEquals(app.resolve("platform/build/classes/java/test").toRealPath(),loaded);
        for(Class<?> dependency:List.of(ProcurementInventoryNativeAdmission.class,ProcurementPublicSourceNativeFixture.class,
                ProcurementOrderUiFixtureProducerIT.class,ProcurementSourceUiFixtureProducerIT.class,
                com.auraboot.framework.notification.sourcebinding.ProductQuoteNativeInitialization.class,
                com.auraboot.framework.notification.sourcebinding.S06NativeEncryptionAdmission.class))
            assertEquals(loaded,Path.of(dependency.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath());
        String resource="com/auraboot/framework/common/crypto/FieldEncryptionService.class";
        var resources=java.util.Collections.list(FieldEncryptionService.class.getClassLoader().getResources(resource));
        validateProductionClasses(input.path("productionClassAdmission"),app,
                Path.of(FieldEncryptionService.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                java.util.Arrays.stream(FieldEncryptionService.class.getDeclaredMethods()).anyMatch(m->"$jacocoInit".equals(m.getName())),
                resources.stream().map(url->{try{return Path.of(url.toURI());}catch(Exception e){throw new IllegalStateException(e);}}).toList(),json);
        assertEquals(Set.copyOf(SOURCES),keys(input.path("nativeSourceSha256")));
        for(String source:SOURCES)assertEquals(input.path("nativeSourceSha256").path(source).asText(),sha(app.resolve(source)));
        JsonNode classes=input.path("classSha256");assertTrue(classes.isObject() && !classes.isEmpty());
        assertEquals(classInventory(loaded),json.convertValue(classes,new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,String>>(){}));
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
        assertEquals(sha(app.resolve("platform/platform-app/src/main/resources/application.yml")),proof.path("applicationYmlSha256").asText());
        String admittedKey=com.auraboot.framework.notification.sourcebinding.S06NativeEncryptionAdmission.requiredKey(System.getenv());
        assertTrue(java.security.MessageDigest.isEqual(matchingKey.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                admittedKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "Receipt crypto aliases must match the same admitted runtime value");
        var matching=com.auraboot.framework.notification.sourcebinding.S06NativeEncryptionAdmission.admit(
                System.getenv(),json,encryption,ProcurementInventoryReceiptRealDbIT.class,actualNativeProfiles);
        assertEquals(proofPath.toString(),matching.get("proofPath"));
        assertEquals(input.path("encryptionConfigProofSha256").asText(),matching.get("proofSha256"));
        String mode=matching.get("mode").toString();
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
    static void validateProductionClasses(JsonNode proof,Path app,Path loaded,boolean instrumented,List<Path> resources,ObjectMapper json) throws Exception {
        assertEquals(Set.of("mode","gradlePath","gradleSha256","originalRoot","instrumentedRoot","originalClassesSha256","instrumentedClassesSha256"),keys(proof));
        assertEquals("jacoco-offline",proof.path("mode").asText());
        Path original=app.resolve("platform/build/classes/java/main"),offline=app.resolve("platform/build/classes-instrumented-main"),gradle=app.resolve("platform/build.gradle");
        for(var pair:java.util.Map.of("originalRoot",original,"instrumentedRoot",offline,"gradlePath",gradle).entrySet()){
            assertFalse(Files.isSymbolicLink(pair.getValue()));assertEquals(pair.getValue().toRealPath().toString(),proof.path(pair.getKey()).asText());
        }
        assertEquals(sha(gradle),proof.path("gradleSha256").asText());
        var before=productionClassInventory(original);var after=productionClassInventory(offline);
        assertEquals(before.keySet(),after.keySet());assertFalse(before.isEmpty());
        var type=new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,String>>(){};
        assertEquals(before,json.convertValue(proof.path("originalClassesSha256"),type));
        assertEquals(after,json.convertValue(proof.path("instrumentedClassesSha256"),type));
        assertEquals(offline.toRealPath(),loaded.toRealPath());assertTrue(instrumented,"Actual production class must contain $jacocoInit");
        assertEquals(List.of(offline.resolve("com/auraboot/framework/common/crypto/FieldEncryptionService.class").toRealPath()),
                resources.stream().map(path->{try{return path.toRealPath();}catch(Exception e){throw new IllegalStateException(e);}}).toList());
    }
    static java.util.Map<String,String> productionClassInventory(Path root) throws Exception {
        assertFalse(Files.isSymbolicLink(root));Path real=root.toRealPath();var result=new java.util.TreeMap<String,String>();
        try(var paths=Files.walk(root)){
            for(Path path:paths.toList()){
                assertFalse(Files.isSymbolicLink(path));
                if(Files.isRegularFile(path) && path.toString().endsWith(".class")){
                    assertTrue(path.toRealPath().startsWith(real));result.put(root.relativize(path).toString(),sha(path));
                }
            }
        }
        assertFalse(result.isEmpty());return java.util.Map.copyOf(result);
    }
    static void validateApplicationIdentity(JsonNode input,String actualAppCommit,String dirty,String mainDiff) {
        String app=input.path("applicationCoreCommit").asText(),runtime=input.path("runtimeCoreCommit").asText();
        assertTrue(app.matches("[a-f0-9]{40}"));assertTrue(runtime.matches("[a-f0-9]{40}"));
        assertEquals(Set.of("oss","quote","enterprise","crm","plugins"),keys(input.path("sourceSet")));
        for(var pin:input.path("sourceSet"))assertTrue(pin.isTextual() && pin.asText().matches("[a-f0-9]{40}"));
        assertEquals(runtime,input.path("sourceSet").path("oss").asText());
        assertEquals(app,actualAppCommit);assertEquals("",dirty);assertEquals("",mainDiff);
    }
    static java.util.Map<String,String> classInventory(Path loaded) throws Exception {
        var result=new java.util.TreeMap<String,String>();
        for(String source:SOURCES){
            String relative=source.replace("platform/src/test/java/","").replace(".java","");
            Path outer=loaded.resolve(relative+".class");assertTrue(Files.isRegularFile(outer));
            String name=outer.getFileName().toString().replace(".class","");
            try(var files=Files.list(outer.getParent())){
                for(Path file:files.filter(path->path.getFileName().toString().equals(name+".class")
                        || path.getFileName().toString().startsWith(name+"$") && path.getFileName().toString().endsWith(".class")).toList()){
                    assertFalse(Files.isSymbolicLink(file));result.put(file.toRealPath().toString(),sha(file));
                }
            }
        }
        return java.util.Map.copyOf(result);
    }
    static Set<String> keys(JsonNode node){assertTrue(node.isObject());var keys=new java.util.HashSet<String>();node.fieldNames().forEachRemaining(keys::add);return Set.copyOf(keys);}
    private static String sha(Path path) throws Exception{return ProcurementPublicSourceNativeFixture.sha(path);}
}
