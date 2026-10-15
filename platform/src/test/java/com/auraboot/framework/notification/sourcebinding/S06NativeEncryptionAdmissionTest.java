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
                .put("schemaVersion", 1).put("runtime", "procurement-web-stack-20261010")
                .put("configProperty", "${FIELD_ENCRYPTION_KEY:}").put("mode", mode)
                .put("runtimeMode", "development").put("actualSpringProfiles", "dev,local,test")
                .put("environmentKeyState", mode.equals("encrypted") ? "SET" : "UNSET");
    }
}
