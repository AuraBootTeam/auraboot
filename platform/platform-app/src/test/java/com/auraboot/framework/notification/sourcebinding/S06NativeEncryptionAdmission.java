package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Properties;
import java.util.Set;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Test-only admission. Runtime profiles are evidence, not the Native application's profile identity. */
public final class S06NativeEncryptionAdmission {
    static final String KEY = "S06_NATIVE_FIELD_ENCRYPTION_KEY";
    private static Path privateProperties() { return NativeProcurementWorkspace.root()
            .resolve(".workspace/private").resolve(RUNTIME).resolve("field-encryption.properties"); }
    private static final Set<String> OVERRIDES = Set.of("jvmFieldEncryptionKey", "commandLineFieldEncryptionKey",
            "springApplicationJson", "profileSpecificFieldEncryptionKey", "environmentConfigLocation");
    private static final Set<String> PRIVATE_SOURCE_FIELDS = Set.of("schemaVersion", "kind", "runtime", "backendPid",
            "environmentKeyState", "effectiveKeyState", "additionalLocationCount", "additionalLocation",
            "propertiesPath", "propertiesSha256", "overridesAbsent");
    private static final String RUNTIME = NativeProcurementWorkspace.runtime();

    private S06NativeEncryptionAdmission() {}

    public static String requiredKey(Map<String, String> environment) {
        require(environment.containsKey(KEY) && environment.get(KEY) != null, "S06_MATCHING_KEY_MUST_BE_SET");
        return environment.get(KEY);
    }

    public static Map<String, Object> admit(Map<String, String> environment, ObjectMapper json,
            FieldEncryptionService bean, Class<?> testClass, String[] nativeProfiles) throws Exception {
        String key = requiredKey(environment);
        Path app = Path.of(required(environment, "S06_NATIVE_APPLICATION_ROOT")).toRealPath();
        require(Files.isSameFile(Path.of(testClass.getProtectionDomain().getCodeSource().getLocation().toURI()),
                app.resolve("platform/platform-app/build/classes/java/test")), "S06_NATIVE_APPLICATION_CODE_SOURCE_MISMATCH");
        Path evidence = NativeProcurementWorkspace.root().resolve(".workspace/evidence").resolve(RUNTIME).toRealPath();
        Path proofPath = Path.of(required(environment, "S06_NATIVE_ENCRYPTION_PROOF_PATH")).toRealPath();
        require(proofPath.startsWith(evidence), "S06_ENCRYPTION_PROOF_NOT_OWNED");
        String proofHash = hash(proofPath);
        require(proofHash.equals(required(environment, "S06_NATIVE_ENCRYPTION_PROOF_SHA256")), "S06_ENCRYPTION_PROOF_DRIFT");
        JsonNode proof = json.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .readTree(Files.readString(proofPath));
        String mode = mode(proof, key);
        require(hash(app.resolve("platform/src/main/java/com/auraboot/framework/common/crypto/FieldEncryptionService.java"))
                .equals(proof.path("fieldEncryptionServiceSha256").asText()), "S06_ENCRYPTION_SERVICE_SOURCE_DRIFT");
        require(hash(app.resolve("platform/platform-app/src/main/resources/application.yml"))
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
        if (proof.path("keySource").asText().equals("private-additional-properties")) {
            privateBackend(proof, runtime, envProof, profiles);
            JsonNode source = privateSourceEvidence(proof, evidence, json);
            privateSource(proof, source, privateProperties(), key);
        }
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
        boolean privateSource = proof.path("keySource").asText().equals("private-additional-properties");
        if (privateSource) {
            require(proof.path("environmentKeyState").asText().equals("UNSET")
                    && proof.path("effectiveKeyState").asText().equals("SET")
                    && proof.path("backendPid").isIntegralNumber() && proof.path("backendPid").canConvertToLong()
                    && proof.path("backendPid").longValue() > 0
                    && proof.path("privateSourceEvidencePath").isTextual()
                    && !proof.path("privateSourceEvidencePath").textValue().isBlank()
                    && Path.of(proof.path("privateSourceEvidencePath").textValue()).isAbsolute()
                    && proof.path("privateSourceEvidenceSha256").isTextual()
                    && proof.path("privateSourceEvidenceSha256").textValue().matches("[a-f0-9]{64}"),
                    "S06_PRIVATE_SOURCE_PROOF_INVALID");
        } else {
            require(!proof.has("keySource") && !proof.has("privateSourceEvidencePath") && !proof.has("privateSourceEvidenceSha256"),
                    "S06_PRIVATE_SOURCE_PROOF_INVALID");
        }
        String mode = proof.path("mode").asText();
        String profiles = proof.path("actualSpringProfiles").asText().toLowerCase(Locale.ROOT);
        String state = proof.path("environmentKeyState").asText();
        if (mode.equals("dev-passthrough")) {
            require(List.of("development", "verification").contains(proof.path("runtimeMode").asText())
                    && !privateSource && List.of("UNSET", "EMPTY").contains(state) && key.isBlank() && !profiles.contains("prod")
                    && (profiles.isBlank() || profiles.contains("dev") || profiles.contains("local") || profiles.contains("test")),
                    "S06_PASSTHROUGH_NOT_MATCHING_RUNTIME");
        } else {
            require(mode.equals("encrypted") && (privateSource || state.equals("SET")) && !key.isBlank(), "S06_ENCRYPTION_MODE_INVALID");
            byte[] bytes;
            try { bytes = Base64.getDecoder().decode(key); }
            catch (IllegalArgumentException invalid) { throw new IllegalStateException("S06_MANAGED_KEY_ENCODING_INVALID"); }
            require(bytes.length == 32, "S06_MANAGED_KEY_LENGTH_INVALID");
        }
        return mode;
    }

    static void privateBackend(JsonNode proof, JsonNode runtime, JsonNode environment, JsonNode profiles) {
        JsonNode pid = proof.path("backendPid");
        require(runtime.path("processes").isArray(), "S06_PRIVATE_SOURCE_BACKEND_MISMATCH");
        var backends = java.util.stream.StreamSupport.stream(runtime.path("processes").spliterator(), false)
                .filter(row -> row.path("key").asText().equals("backend")).toList();
        require(backends.size() == 1 && backends.getFirst().path("status").asText().equals("ok")
                && backends.getFirst().path("actual").path("alive").isBoolean()
                && backends.getFirst().path("actual").path("alive").booleanValue(), "S06_PRIVATE_SOURCE_BACKEND_MISMATCH");
        JsonNode actual = backends.getFirst().path("expected").path("pid");
        require(pid.isIntegralNumber() && pid.canConvertToLong() && pid.longValue() > 0
                && actual.isIntegralNumber() && actual.canConvertToLong() && pid.longValue() == actual.longValue(),
                "S06_PRIVATE_SOURCE_BACKEND_MISMATCH");
        for (JsonNode referent : List.of(environment, profiles)) {
            if (referent.has("backendPid")) require(referent.path("backendPid").isIntegralNumber()
                    && referent.path("backendPid").canConvertToLong()
                    && referent.path("backendPid").longValue() == pid.longValue(), "S06_PRIVATE_SOURCE_BACKEND_MISMATCH");
        }
    }

    static JsonNode privateSourceEvidence(JsonNode proof, Path evidence, ObjectMapper json) throws Exception {
        Path declared = Path.of(proof.path("privateSourceEvidencePath").textValue());
        require(!Files.isSymbolicLink(declared), "S06_PRIVATE_SOURCE_REFERENT_INVALID");
        Path sourcePath = declared.toRealPath();
        require(sourcePath.startsWith(evidence.toRealPath()), "S06_PRIVATE_SOURCE_REFERENT_INVALID");
        ownedPrivateFile(sourcePath);
        return referent(proof, "privateSourceEvidence", evidence.toRealPath(), json);
    }

    /** Only admit() supplies the literal Native target; tests supply their own fixed temporary target. */
    static void privateSource(JsonNode proof, JsonNode source, Path fixedTarget, String key) throws Exception {
        require(mode(proof, key).equals("encrypted")
                && proof.path("keySource").asText().equals("private-additional-properties"), "S06_PRIVATE_SOURCE_PROOF_INVALID");
        var sourceFields = new java.util.HashSet<String>();
        source.fieldNames().forEachRemaining(sourceFields::add);
        boolean roster = source.path("schemaVersion").isInt() && source.path("schemaVersion").intValue() == 2;
        var expectedFields = new java.util.HashSet<>(PRIVATE_SOURCE_FIELDS);
        if (roster) expectedFields.add("additionalLocations");
        require(source.isObject() && sourceFields.equals(expectedFields)
                && source.path("schemaVersion").isInt() && source.path("schemaVersion").intValue() == (roster ? 2 : 1)
                && source.path("kind").asText().equals("procurement-private-additional-properties-evidence-v" + (roster ? "2" : "1"))
                && source.path("runtime").asText().equals(RUNTIME)
                && source.path("backendPid").isIntegralNumber() && source.path("backendPid").equals(proof.path("backendPid"))
                && source.path("environmentKeyState").asText().equals("UNSET")
                && source.path("effectiveKeyState").asText().equals("SET")
                && source.path("additionalLocationCount").isInt() && source.path("additionalLocationCount").intValue() >= 1
                && source.path("additionalLocationCount").intValue() <= 16
                && (roster || source.path("additionalLocationCount").intValue() == 1)
                && source.path("propertiesPath").isTextual()
                && source.path("propertiesPath").textValue().equals(fixedTarget.toString())
                && source.path("additionalLocation").isTextual()
                && (roster || source.path("additionalLocation").textValue().equals("file:" + fixedTarget))
                && source.path("propertiesSha256").isTextual()
                && source.path("propertiesSha256").textValue().matches("[a-f0-9]{64}"), "S06_PRIVATE_SOURCE_EVIDENCE_INVALID");
        JsonNode overrides = source.path("overridesAbsent");
        var fields = new java.util.HashSet<String>();
        overrides.fieldNames().forEachRemaining(fields::add);
        require(overrides.isObject() && fields.equals(OVERRIDES)
                && OVERRIDES.stream().allMatch(name -> overrides.path(name).isBoolean() && overrides.path(name).booleanValue()),
                "S06_PRIVATE_SOURCE_OVERRIDE_CONFLICT");
        require(fixedTarget.isAbsolute() && fixedTarget.normalize().equals(fixedTarget)
                && fixedTarget.toRealPath().equals(fixedTarget), "S06_PRIVATE_SOURCE_PATH_INVALID");
        if (roster) validatePrivateRoster(source, fixedTarget);
        ownedPrivateFile(fixedTarget);
        require(Files.size(fixedTarget) <= 65536, "S06_PRIVATE_SOURCE_FILE_INVALID");
        byte[] bytes = Files.readAllBytes(fixedTarget);
        require(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                .equals(source.path("propertiesSha256").textValue()), "S06_PRIVATE_SOURCE_FILE_DRIFT");
        Properties properties = new Properties() {
            @Override public synchronized Object put(Object name, Object value) {
                require(!containsKey(name), "S06_PRIVATE_SOURCE_PROPERTY_INVALID");
                return super.put(name, value);
            }
        };
        try { properties.load(new StringReader(new String(bytes, StandardCharsets.UTF_8))); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("S06_PRIVATE_SOURCE_PROPERTY_INVALID"); }
        require(properties.size() == 1 && properties.containsKey("security.field-encryption.key"),
                "S06_PRIVATE_SOURCE_PROPERTY_INVALID");
        require(MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),
                properties.getProperty("security.field-encryption.key").getBytes(StandardCharsets.UTF_8)),
                "S06_PRIVATE_SOURCE_KEY_MISMATCH");
    }

    /** Explicit ordered roster: every additional file is owned, sealed and unable to shadow the key. */
    private static void validatePrivateRoster(JsonNode source, Path fixedTarget) throws Exception {
        JsonNode locations = source.path("additionalLocations");
        require(locations.isArray() && locations.size() == source.path("additionalLocationCount").intValue(),
                "S06_PRIVATE_SOURCE_EVIDENCE_INVALID");
        var paths = new java.util.HashSet<Path>();
        var uris = new java.util.ArrayList<String>();
        for (JsonNode location : locations) {
            var fields = new java.util.HashSet<String>();
            location.fieldNames().forEachRemaining(fields::add);
            require(location.isObject() && fields.equals(Set.of("path", "sha256"))
                    && location.path("path").isTextual() && location.path("sha256").isTextual()
                    && location.path("sha256").asText().matches("[a-f0-9]{64}"), "S06_PRIVATE_SOURCE_EVIDENCE_INVALID");
            Path path = Path.of(location.path("path").textValue());
            require(path.isAbsolute() && path.normalize().equals(path) && path.toRealPath().equals(path)
                    && path.getParent().equals(fixedTarget.getParent()) && paths.add(path)
                    && path.getFileName().toString().endsWith(".properties"), "S06_PRIVATE_SOURCE_PATH_INVALID");
            ownedPrivateFile(path);
            require(Files.size(path) <= 65536, "S06_PRIVATE_SOURCE_FILE_INVALID");
            require(hash(path).equals(location.path("sha256").textValue()), "S06_PRIVATE_SOURCE_FILE_DRIFT");
            uris.add(path.toUri().toString());
            if (!path.equals(fixedTarget)) {
                Properties other = new Properties();
                other.load(new StringReader(Files.readString(path, StandardCharsets.UTF_8)));
                for (String name : other.stringPropertyNames()) {
                    String normalized = name.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
                    require(!normalized.equals("securityfieldencryptionkey") && !normalized.equals("fieldencryptionkey")
                            && !normalized.startsWith("springconfig") && !normalized.startsWith("springprofiles"),
                            "S06_PRIVATE_SOURCE_OVERRIDE_CONFLICT");
                }
            }
        }
        require(paths.contains(fixedTarget) && String.join(",", uris).equals(source.path("additionalLocation").textValue()),
                "S06_PRIVATE_SOURCE_EVIDENCE_INVALID");
    }

    private static void ownedPrivateFile(Path path) throws Exception {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
                && Files.getPosixFilePermissions(path).equals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
                && Files.getOwner(path).equals(path.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"))), "S06_PRIVATE_SOURCE_FILE_INVALID");
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
        return json.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(Files.readString(path));
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
