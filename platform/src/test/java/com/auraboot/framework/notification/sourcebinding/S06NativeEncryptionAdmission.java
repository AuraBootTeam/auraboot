package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Test-only admission. Runtime profiles are evidence, not the Native application's profile identity. */
final class S06NativeEncryptionAdmission {
    static final String KEY = "S06_NATIVE_FIELD_ENCRYPTION_KEY";
    private static final String RUNTIME = "procurement-web-stack-20261010";

    private S06NativeEncryptionAdmission() {}

    static String requiredKey(Map<String, String> environment) {
        require(environment.containsKey(KEY) && environment.get(KEY) != null, "S06_MATCHING_KEY_MUST_BE_SET");
        return environment.get(KEY);
    }

    static Map<String, Object> admit(Map<String, String> environment, ObjectMapper json,
            FieldEncryptionService bean, Class<?> testClass, String[] nativeProfiles) throws Exception {
        String key = requiredKey(environment);
        Path app = Path.of(required(environment, "S06_NATIVE_APPLICATION_ROOT")).toRealPath();
        require(Files.isSameFile(Path.of(testClass.getProtectionDomain().getCodeSource().getLocation().toURI()),
                app.resolve("platform/build/classes/java/test")), "S06_NATIVE_APPLICATION_CODE_SOURCE_MISMATCH");
        Path evidence = Path.of("/Users/ghj/work/auraboot/.workspace/evidence/" + RUNTIME).toRealPath();
        Path proofPath = Path.of(required(environment, "S06_NATIVE_ENCRYPTION_PROOF_PATH")).toRealPath();
        require(proofPath.startsWith(evidence), "S06_ENCRYPTION_PROOF_NOT_OWNED");
        String proofHash = hash(proofPath);
        require(proofHash.equals(required(environment, "S06_NATIVE_ENCRYPTION_PROOF_SHA256")), "S06_ENCRYPTION_PROOF_DRIFT");
        JsonNode proof = json.readTree(proofPath.toFile());
        String mode = mode(proof, key);
        require(hash(app.resolve("platform/src/main/java/com/auraboot/framework/common/crypto/FieldEncryptionService.java"))
                .equals(proof.path("fieldEncryptionServiceSha256").asText()), "S06_ENCRYPTION_SERVICE_SOURCE_DRIFT");
        require(hash(app.resolve("platform/src/main/resources/application.yml"))
                .equals(proof.path("applicationYmlSha256").asText()), "S06_ENCRYPTION_CONFIGURATION_SOURCE_DRIFT");
        JsonNode runtime = referent(proof, "runtimeAdmission", evidence, json);
        require(runtime.path("runtime").asText().equals(RUNTIME) && runtime.path("verdict").asText().equals("ok"),
                "S06_ENCRYPTION_RUNTIME_NOT_VERIFIED");
        require(runtime.path("metadata").path("mode").asText().equals(proof.path("runtimeMode").asText()),
                "S06_ENCRYPTION_RUNTIME_MODE_MISMATCH");
        var cores = java.util.stream.StreamSupport.stream(runtime.path("sources").spliterator(), false)
                .filter(row -> row.path("key").asText().equals("oss")).toList();
        require(cores.size() == 1 && proof.path("sourceCommit").asText().matches("[a-f0-9]{40}")
                && cores.getFirst().path("actual").path("commit").asText().equals(proof.path("sourceCommit").asText()),
                "S06_ENCRYPTION_RUNTIME_SOURCE_MISMATCH");
        JsonNode envProof = referent(proof, "runtimeEnvironmentEvidence", evidence, json);
        require(envProof.path("runtime").asText().equals(RUNTIME) && envProof.path("fieldEncryptionKeyState").asText()
                .equals(proof.path("environmentKeyState").asText()), "S06_ENCRYPTION_ENVIRONMENT_MISMATCH");
        JsonNode profiles = referent(proof, "runtimeProfileEvidence", evidence, json);
        require(profiles.path("runtime").asText().equals(RUNTIME) && profiles.path("actualSpringProfiles").asText()
                .equals(proof.path("actualSpringProfiles").asText()), "S06_ENCRYPTION_RUNTIME_PROFILES_MISMATCH");
        probe(mode, bean);
        return Map.of("mode", mode, "proofPath", proofPath.toString(), "proofSha256", proofHash,
                "runtimeActualSpringProfiles", proof.path("actualSpringProfiles").asText(),
                "nativeActualSpringProfiles", List.of(nativeProfiles), "nonSecretBeanProbeMatched", true);
    }

    static String mode(JsonNode proof, String key) {
        require(key != null, "S06_MATCHING_KEY_MUST_BE_SET");
        require(proof.isObject() && proof.path("schemaVersion").isInt() && proof.path("schemaVersion").intValue() == 1
                && proof.path("kind").asText().equals("procurement-native-runtime-encryption-proof-v1")
                && proof.path("runtime").asText().equals(RUNTIME)
                && proof.path("configProperty").asText().equals("${FIELD_ENCRYPTION_KEY:}")
                && proof.path("actualSpringProfiles").isTextual(), "S06_ENCRYPTION_PROOF_SCHEMA_INVALID");
        String mode = proof.path("mode").asText();
        String profiles = proof.path("actualSpringProfiles").asText().toLowerCase(Locale.ROOT);
        String state = proof.path("environmentKeyState").asText();
        if (mode.equals("dev-passthrough")) {
            require(List.of("development", "verification").contains(proof.path("runtimeMode").asText())
                    && List.of("UNSET", "EMPTY").contains(state) && key.isBlank() && !profiles.contains("prod")
                    && (profiles.isBlank() || profiles.contains("dev") || profiles.contains("local") || profiles.contains("test")),
                    "S06_PASSTHROUGH_NOT_MATCHING_RUNTIME");
        } else {
            require(mode.equals("encrypted") && state.equals("SET") && !key.isBlank(), "S06_ENCRYPTION_MODE_INVALID");
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(key); }
            catch (IllegalArgumentException invalid) { throw new IllegalStateException("S06_MANAGED_KEY_ENCODING_INVALID"); }
            require(bytes.length == 32, "S06_MANAGED_KEY_LENGTH_INVALID");
        }
        return mode;
    }

    static void probe(String mode, FieldEncryptionService bean) {
        require(List.of("dev-passthrough", "encrypted").contains(mode), "S06_ENCRYPTION_MODE_INVALID");
        require(bean.isEnabled() == mode.equals("encrypted"), "S06_ACTUAL_BEAN_MODE_MISMATCH");
        String original = "S06NonSecretEncryptionProbe";
        String encoded = bean.encrypt(original);
        require(original.equals(bean.decrypt(encoded)), "S06_ACTUAL_BEAN_ROUNDTRIP_FAILED");
        require(mode.equals("encrypted") ? encoded.startsWith("ENC:") : original.equals(encoded), "S06_ACTUAL_BEAN_FORMAT_MISMATCH");
    }

    private static JsonNode referent(JsonNode proof, String prefix, Path evidence, ObjectMapper json) throws Exception {
        Path path = Path.of(proof.path(prefix + "Path").asText()).toRealPath();
        require(path.startsWith(evidence) && hash(path).equals(proof.path(prefix + "Sha256").asText()),
                "S06_ENCRYPTION_REFERENT_DRIFT");
        return json.readTree(path.toFile());
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        require(value != null && !value.isBlank(), "S06_CRYPTO_INPUT_REQUIRED:" + name);
        return value;
    }

    private static String hash(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static void require(boolean valid, String code) {
        if (!valid) throw new IllegalStateException(code);
    }
}
