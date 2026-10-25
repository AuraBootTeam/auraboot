package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Hermetic proof-mode and actual bean contract; no Runtime/PG/provider qualification. */
class S06NativeEncryptionAdmissionTest {
    @Test
    void missingKeyIsRejectedButExplicitEmptyIsRetained() {
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.requiredKey(Map.of()))
                .hasMessage("S06_MATCHING_KEY_MUST_BE_SET");
        assertThat(S06NativeEncryptionAdmission.requiredKey(Map.of(S06NativeEncryptionAdmission.KEY, ""))).isEmpty();
    }

    @Test
    void nativeAnnotationCannotOverrideMatchingKeyWithAnEmbeddedValue() {
        String[] properties = RegisteredSourceBindingPostgresIT.class.getAnnotation(SpringBootTest.class).properties();
        assertThat(Arrays.stream(properties).filter(value -> value.startsWith("security.field-encryption.key="))).isEmpty();
    }

    @Test
    void explicitPassthroughMatchesDevProofButNeverProductionOrNonemptyKey() {
        ObjectNode proof = proof("dev-passthrough");
        assertThat(S06NativeEncryptionAdmission.mode(proof, "")).isEqualTo("dev-passthrough");
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.mode(proof, key()))
                .hasMessage("S06_PASSTHROUGH_NOT_MATCHING_RUNTIME");
        proof.put("actualSpringProfiles", "prod,test");
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.mode(proof, ""))
                .hasMessage("S06_PASSTHROUGH_NOT_MATCHING_RUNTIME");
    }

    @Test
    void encryptedModeRequiresActualExplicitAes256KeyAndKnownMode() {
        ObjectNode proof = proof("encrypted");
        assertThat(S06NativeEncryptionAdmission.mode(proof, key())).isEqualTo("encrypted");
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.mode(proof, ""))
                .hasMessage("S06_ENCRYPTION_MODE_INVALID");
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.mode(proof, "bad?"))
                .hasMessage("S06_MANAGED_KEY_ENCODING_INVALID");
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.mode(proof, Base64.getEncoder().encodeToString(new byte[16])))
                .hasMessage("S06_MANAGED_KEY_LENGTH_INVALID");
        proof.put("mode", "unknown");
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.mode(proof, key()))
                .hasMessage("S06_ENCRYPTION_MODE_INVALID");
    }

    @Test
    void wrongKindAndKeyStateCannotSelfAuthorizeMatchingMode() {
        ObjectNode proof = proof("dev-passthrough");
        proof.put("kind", "arbitrary-proof");
        ObjectNode wrongKind = proof;
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.mode(wrongKind, ""))
                .hasMessage("S06_ENCRYPTION_PROOF_SCHEMA_INVALID");
        proof = proof("dev-passthrough");
        proof.put("environmentKeyState", "SET");
        ObjectNode wrongState = proof;
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.mode(wrongState, ""))
                .hasMessage("S06_PASSTHROUGH_NOT_MATCHING_RUNTIME");
    }

    @Test
    void matchingKeyCannotAdmitAProofForTheOtherAllowedRuntime() {
        ObjectNode proof = proof("encrypted");
        proof.put("runtime", NativeProcurementWorkspace.runtime().equals("procurement-main-validation")
                ? "procurement-web-stack-20261010" : "procurement-main-validation");
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.mode(proof, key()))
                .hasMessage("S06_ENCRYPTION_PROOF_SCHEMA_INVALID");
    }

    @Test
    void realEncryptionBeansMustMatchModeAndRoundTripWithoutLeakingSecrets() {
        FieldEncryptionService blank = bean("");
        S06NativeEncryptionAdmission.probe("dev-passthrough", blank);
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.probe("encrypted", blank))
                .hasMessage("S06_ACTUAL_BEAN_MODE_MISMATCH");
        FieldEncryptionService encrypted = bean(key());
        S06NativeEncryptionAdmission.probe("encrypted", encrypted);
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.probe("dev-passthrough", encrypted))
                .hasMessage("S06_ACTUAL_BEAN_MODE_MISMATCH");
    }

    @Test
    void privateAdditionalPropertiesModeUsesEffectiveKeyWithoutInventingEnvironmentSet() {
        ObjectNode proof = privateProof();
        assertThat(S06NativeEncryptionAdmission.mode(proof, key())).isEqualTo("encrypted");
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.mode(proof, ""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void privateProofMissingFieldsOrConflictingEnvironmentCannotAuthorizeMode() {
        for (String field : java.util.List.of("privateSourceEvidencePath", "privateSourceEvidenceSha256", "effectiveKeyState", "backendPid")) {
            ObjectNode proof = privateProof();
            proof.remove(field);
            assertThatThrownBy(() -> S06NativeEncryptionAdmission.mode(proof, key()))
                    .isInstanceOf(IllegalStateException.class);
        }
        ObjectNode conflicting = privateProof();
        conflicting.put("environmentKeyState", "SET");
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.mode(conflicting, key())).isInstanceOf(IllegalStateException.class);
        ObjectNode proof = privateProof();
        proof.put("privateSourceEvidenceSha256", "wrong");
        ObjectNode malformed = proof;
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.mode(malformed, key())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fixedOwnedPrivatePropertiesMustMatchNativeKeyAndRecordedBytes(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        java.nio.file.Path target = temp.toRealPath().resolve("field-encryption.properties");
        writePrivate(target, "security.field-encryption.key=" + key() + "\n");
        ObjectNode proof = privateProof();
        ObjectNode source = source(proof, target);
        S06NativeEncryptionAdmission.privateSource(proof, source, target, key());
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSource(proof, source, target,
                Base64.getEncoder().encodeToString(new byte[32]) + "different"))
                .isInstanceOf(IllegalStateException.class);
        writePrivate(target, "security.field-encryption.key=" + Base64.getEncoder().encodeToString(new byte[16]) + "\n");
        ObjectNode changed = source(proof, target);
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSource(proof, changed, target, key()))
                .hasMessage("S06_PRIVATE_SOURCE_KEY_MISMATCH");
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSource(proof, source, target, key()))
                .hasMessage("S06_PRIVATE_SOURCE_FILE_DRIFT");
    }

    @Test
    void duplicateUnknownOrMissingPropertyCannotSelectAKey(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        java.nio.file.Path target = temp.toRealPath().resolve("field-encryption.properties");
        ObjectNode proof = privateProof();
        for (String contents : java.util.List.of("", "unrelated=value\n",
                "security.field-encryption.key=" + key() + "\nsecurity.field-encryption.key=" + key() + "\n",
                "security.field-encryption.key=" + key() + "\nunrelated=value\n",
                "security.field-encryption.key=" + key() + "\nsecurity.field-encryption.\u006bey=" + key() + "\n")) {
            writePrivate(target, contents);
            ObjectNode source = source(proof, target);
            assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSource(proof, source, target, key()))
                    .hasMessage("S06_PRIVATE_SOURCE_PROPERTY_INVALID");
        }
    }

    @Test
    void privateEvidenceMustMatchActualPidUniqueLocationAndRealBooleanOverrides(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        java.nio.file.Path target = temp.toRealPath().resolve("field-encryption.properties");
        writePrivate(target, "security.field-encryption.key=" + key() + "\n");
        ObjectNode proof = privateProof();
        for (String name : java.util.List.of("backendPid", "additionalLocation", "additionalLocationCount", "propertiesPath", "propertiesSha256", "overridesAbsent")) {
            ObjectNode invalid = source(proof, target); invalid.remove(name);
            assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSource(proof, invalid, target, key()))
                    .isInstanceOf(IllegalStateException.class);
        }
        for (Object value : java.util.List.of("true", false)) {
            ObjectNode invalid = source(proof, target);
            if (value instanceof String text) ((ObjectNode) invalid.get("overridesAbsent")).put("jvmFieldEncryptionKey", text);
            else ((ObjectNode) invalid.get("overridesAbsent")).put("jvmFieldEncryptionKey", false);
            assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSource(proof, invalid, target, key()))
                    .hasMessage("S06_PRIVATE_SOURCE_OVERRIDE_CONFLICT");
        }
        ObjectNode wrongPid = source(proof, target).put("backendPid", 9999);
        ObjectNode duplicateLocation = source(proof, target).put("additionalLocationCount", 2);
        ObjectNode wrongLocation = source(proof, target).put("additionalLocation", "file:" + target + ",file:/untrusted");
        ObjectNode extraOverride = source(proof, target);
        ((ObjectNode) extraOverride.get("overridesAbsent")).put("unknown", true);
        ObjectNode unknown = source(proof, target).put("undeclared", true);
        for (ObjectNode invalid : java.util.List.of(wrongPid,duplicateLocation,wrongLocation,extraOverride,unknown))
            assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSource(proof, invalid, target, key()))
                    .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void privateSourceCannotReadDifferentPathSymlinkOrBroadPermissions(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        java.nio.file.Path target = temp.toRealPath().resolve("field-encryption.properties");
        writePrivate(target, "security.field-encryption.key=" + key() + "\n");
        ObjectNode proof = privateProof();
        java.nio.file.Path other = temp.resolve("other.properties").toAbsolutePath();
        writePrivate(other, "security.field-encryption.key=" + key() + "\n");
        ObjectNode wrongPath = source(proof, other);
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSource(proof, wrongPath, target, key()))
                .hasMessage("S06_PRIVATE_SOURCE_EVIDENCE_INVALID");
        java.nio.file.Path link = temp.resolve("linked.properties").toAbsolutePath();
        java.nio.file.Files.createSymbolicLink(link, target);
        ObjectNode linked = source(proof, link);
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSource(proof, linked, link, key()))
                .hasMessage("S06_PRIVATE_SOURCE_PATH_INVALID");
        ObjectNode source = source(proof, target);
        java.nio.file.Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSource(proof, source, target, key()))
                .hasMessage("S06_PRIVATE_SOURCE_FILE_INVALID");
    }

    @Test
    void privateReferentMustBeOwnedHashedPrivateAndDuplicateFree(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        java.nio.file.Path owned = java.nio.file.Files.createDirectory(temp.toRealPath().resolve("evidence"));
        java.nio.file.Path file = owned.resolve("private-source.json");
        writePrivate(file, "{\"safe\":true}");
        ObjectNode proof = privateProof().put("privateSourceEvidencePath",file.toString());
        proof.put("privateSourceEvidenceSha256",fileHash(file));
        assertThat(S06NativeEncryptionAdmission.privateSourceEvidence(proof,owned,new ObjectMapper()).path("safe").booleanValue()).isTrue();
        proof.put("privateSourceEvidenceSha256","0".repeat(64));
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSourceEvidence(proof,owned,new ObjectMapper()))
                .hasMessage("S06_ENCRYPTION_REFERENT_DRIFT");
        java.nio.file.Path outside = temp.toRealPath().resolve("outside.json");writePrivate(outside,"{}");
        proof.put("privateSourceEvidencePath",outside.toString()).put("privateSourceEvidenceSha256",fileHash(outside));
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSourceEvidence(proof,owned,new ObjectMapper()))
                .hasMessage("S06_PRIVATE_SOURCE_REFERENT_INVALID");
        java.nio.file.Path link = owned.resolve("link.json");java.nio.file.Files.createSymbolicLink(link,file);
        proof.put("privateSourceEvidencePath",link.toString()).put("privateSourceEvidenceSha256",fileHash(file));
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSourceEvidence(proof,owned,new ObjectMapper()))
                .hasMessage("S06_PRIVATE_SOURCE_REFERENT_INVALID");
        proof.put("privateSourceEvidencePath",file.toString());
        java.nio.file.Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSourceEvidence(proof,owned,new ObjectMapper()))
                .hasMessage("S06_PRIVATE_SOURCE_FILE_INVALID");
        writePrivate(file,"{\"overridesAbsent\":{\"jvmFieldEncryptionKey\":true,\"jvmFieldEncryptionKey\":true}}");
        proof.put("privateSourceEvidenceSha256",fileHash(file));
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSourceEvidence(proof,owned,new ObjectMapper()))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }

    @Test
    void privateSourceBackendMustBeTheActualRuntimeAndAllDeclaredReferentPids() {
        ObjectMapper json = new ObjectMapper();
        ObjectNode proof = privateProof();
        ObjectNode runtime = json.createObjectNode();
        ObjectNode backend = runtime.putArray("processes").addObject().put("key","backend").put("status","ok");
        backend.putObject("expected").put("pid",1234);
        backend.putObject("actual").put("alive",true);
        ObjectNode env = json.createObjectNode().put("backendPid",1234);
        ObjectNode profiles = json.createObjectNode().put("backendPid",1234);
        S06NativeEncryptionAdmission.privateBackend(proof,runtime,env,profiles);
        for (ObjectNode invalid : java.util.List.of(json.createObjectNode(), json.createObjectNode().put("backendPid","1234"))) {
            assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateBackend(invalid,runtime,env,profiles))
                    .hasMessage("S06_PRIVATE_SOURCE_BACKEND_MISMATCH");
        }
        ObjectNode missing = runtime.deepCopy(); ((ObjectNode)missing.path("processes").get(0).path("expected")).remove("pid");
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateBackend(proof,missing,env,profiles))
                .hasMessage("S06_PRIVATE_SOURCE_BACKEND_MISMATCH");
        for (ObjectNode invalid : java.util.List.of(json.createObjectNode().put("backendPid",9999),json.createObjectNode().put("backendPid","1234"))) {
            assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateBackend(proof,runtime,invalid,profiles))
                    .hasMessage("S06_PRIVATE_SOURCE_BACKEND_MISMATCH");
            assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateBackend(proof,runtime,env,invalid))
                    .hasMessage("S06_PRIVATE_SOURCE_BACKEND_MISMATCH");
        }
        ObjectNode noBackend = json.createObjectNode();noBackend.putArray("processes");
        ObjectNode duplicate = runtime.deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)duplicate.path("processes")).add(backend.deepCopy());
        ObjectNode dead = runtime.deepCopy();((ObjectNode)dead.path("processes").get(0).path("actual")).put("alive",false);
        ObjectNode fakeAlive = runtime.deepCopy();((ObjectNode)fakeAlive.path("processes").get(0).path("actual")).put("alive","true");
        ObjectNode badStatus = runtime.deepCopy();((ObjectNode)badStatus.path("processes").get(0)).put("status","missing");
        ObjectNode noProcesses = json.createObjectNode();noProcesses.putObject("backend").putObject("expected").put("pid",1234);
        for(ObjectNode invalid : java.util.List.of(noBackend,duplicate,dead,fakeAlive,badStatus,noProcesses)) {
            assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateBackend(proof,invalid,env,profiles))
                    .hasMessage("S06_PRIVATE_SOURCE_BACKEND_MISMATCH");
        }
        ObjectNode other = runtime.deepCopy(); ((ObjectNode)other.path("processes").get(0).path("expected")).put("pid",9999);
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateBackend(proof,other,env,profiles))
                .hasMessage("S06_PRIVATE_SOURCE_BACKEND_MISMATCH");
    }

    private static String fileHash(java.nio.file.Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(file)));
    }

    @Test
    void orderedPrivateConfigurationRosterAdmitsOneKeyAndRejectsShadowingOrDrift(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var target = temp.toRealPath().resolve("field-encryption.properties");
        var unrelated = temp.toRealPath().resolve("notification-source.properties");
        writePrivate(target, "security.field-encryption.key=" + key() + "\n");
        writePrivate(unrelated, "aura.notification.source.enabled=true\n");
        ObjectNode proof = privateProof();
        ObjectNode source = source(proof, target).put("schemaVersion", 2)
                .put("kind", "procurement-private-additional-properties-evidence-v2")
                .put("additionalLocationCount", 2)
                .put("additionalLocation", target.toUri() + "," + unrelated.toUri());
        var roster = source.putArray("additionalLocations");
        for (var path : java.util.List.of(target, unrelated)) roster.addObject()
                .put("path", path.toString()).put("sha256", fileHash(path));
        S06NativeEncryptionAdmission.privateSource(proof, source, target, key());
        ObjectNode wrongOrder = source.deepCopy();
        wrongOrder.put("additionalLocation", unrelated.toUri() + "," + target.toUri());
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSource(proof, wrongOrder, target, key()))
                .isInstanceOf(IllegalStateException.class);
        writePrivate(unrelated, "security.fieldEncryption.key=shadow\n");
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSource(proof, source, target, key()))
                .hasMessage("S06_PRIVATE_SOURCE_FILE_DRIFT");
        ((ObjectNode) roster.get(1)).put("sha256", fileHash(unrelated));
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSource(proof, source, target, key()))
                .hasMessage("S06_PRIVATE_SOURCE_OVERRIDE_CONFLICT");
        writePrivate(unrelated, "spring.config.import=optional:file:/elsewhere\n");
        ((ObjectNode) roster.get(1)).put("sha256", fileHash(unrelated));
        assertThatThrownBy(() -> S06NativeEncryptionAdmission.privateSource(proof, source, target, key()))
                .hasMessage("S06_PRIVATE_SOURCE_OVERRIDE_CONFLICT");
        writePrivate(unrelated, "aura.notification.source.enabled=true\n");
        ((ObjectNode) roster.get(1)).put("sha256", fileHash(unrelated));
        S06NativeEncryptionAdmission.privateSource(proof, source, target, key());
    }

    private static void writePrivate(java.nio.file.Path path, String contents) throws Exception {
        java.nio.file.Files.writeString(path, contents);
        java.nio.file.Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }

    private static ObjectNode source(ObjectNode proof, java.nio.file.Path target) throws Exception {
        ObjectNode result = new ObjectMapper().createObjectNode().put("schemaVersion",1)
                .put("kind","procurement-private-additional-properties-evidence-v1")
                .put("runtime",NativeProcurementWorkspace.runtime()).put("backendPid",proof.path("backendPid").intValue())
                .put("environmentKeyState","UNSET").put("effectiveKeyState","SET")
                .put("additionalLocationCount",1).put("additionalLocation","file:" + target)
                .put("propertiesPath",target.toString()).put("propertiesSha256",java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(target))));
        ObjectNode absent = result.putObject("overridesAbsent");
        for (String name : java.util.List.of("jvmFieldEncryptionKey","commandLineFieldEncryptionKey","springApplicationJson",
                "profileSpecificFieldEncryptionKey","environmentConfigLocation")) absent.put(name,true);
        return result;
    }

    private static ObjectNode privateProof() {
        return proof("encrypted").put("keySource", "private-additional-properties")
                .put("environmentKeyState", "UNSET").put("effectiveKeyState", "SET").put("backendPid", 1234)
                .put("privateSourceEvidencePath", "/owned/evidence/private-source.json")
                .put("privateSourceEvidenceSha256", "a".repeat(64));
    }

    private static FieldEncryptionService bean(String key) {
        FieldEncryptionService result = new FieldEncryptionService();
        ReflectionTestUtils.setField(result, "base64Key", key);
        ReflectionTestUtils.setField(result, "activeProfile", "integration-test");
        result.init();
        return result;
    }

    private static String key() {
        return Base64.getEncoder().encodeToString(new byte[32]);
    }

    private static ObjectNode proof(String mode) {
        return new ObjectMapper().createObjectNode().put("kind", "procurement-native-runtime-encryption-proof-v1")
                .put("schemaVersion", 1).put("runtime", NativeProcurementWorkspace.runtime())
                .put("configProperty", "${FIELD_ENCRYPTION_KEY:}").put("mode", mode)
                .put("runtimeMode", "development").put("actualSpringProfiles", "dev,local,test")
                .put("environmentKeyState", mode.equals("encrypted") ? "SET" : "UNSET");
    }
}
