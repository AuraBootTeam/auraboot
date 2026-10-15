package com.auraboot.framework.plugin.pf4j;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

/** Fixture contracts only: actual preparation and browser business behavior remain unexecuted. */
class ProcurementOrderUiFixtureContractTest {
    @Test void managedProviderCredentialUpdatesOnlyExactExistingTenantTuple() {
        var existing = new com.auraboot.framework.cloudconfig.entity.CloudConfig();
        existing.setPid("owned-config"); existing.setTenantId(73L); existing.setConfigLevel("tenant");
        existing.setServiceType("integration"); existing.setProviderCode("fixture-provider");
        existing.setConfig("original-controlled-secret");
        var request = ProcurementOrderUiFixtureProducerIT.managedSecretUpdateRequest(existing, 73L,
                "fixture-provider", "replacement-controlled-secret");
        assertThat(request.getPid()).isEqualTo("owned-config");
        assertThat(request.getConfig()).isEqualTo("replacement-controlled-secret");
        assertThat(existing.getConfig()).isEqualTo("original-controlled-secret");
        assertThat(request.getConfigLevel()).isEqualTo("tenant");
        assertThat(request.getServiceType()).isEqualTo("integration");
        assertThat(request.getProviderCode()).isEqualTo("fixture-provider");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ProcurementOrderUiFixtureProducerIT.managedSecretUpdateRequest(null, 73L, "fixture-provider", "{}"))
                .isInstanceOf(AssertionError.class);
        for (Long foreign : java.util.Arrays.asList(null, 74L)) {
            existing.setTenantId(foreign);
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    ProcurementOrderUiFixtureProducerIT.managedSecretUpdateRequest(existing, 73L, "fixture-provider", "{}"))
                    .isInstanceOf(AssertionError.class);
        }
        existing.setTenantId(73L); existing.setConfigLevel("platform");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ProcurementOrderUiFixtureProducerIT.managedSecretUpdateRequest(existing, 73L, "fixture-provider", "{}"))
                .isInstanceOf(AssertionError.class);
        existing.setConfigLevel("tenant"); existing.setServiceType("oauth");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ProcurementOrderUiFixtureProducerIT.managedSecretUpdateRequest(existing, 73L, "fixture-provider", "{}"))
                .isInstanceOf(AssertionError.class);
        existing.setServiceType("integration"); existing.setProviderCode("other-provider");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ProcurementOrderUiFixtureProducerIT.managedSecretUpdateRequest(existing, 73L, "fixture-provider", "{}"))
                .isInstanceOf(AssertionError.class);
        existing.setProviderCode("fixture-provider"); existing.setPid(" ");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ProcurementOrderUiFixtureProducerIT.managedSecretUpdateRequest(existing, 73L, "fixture-provider", "{}"))
                .isInstanceOf(AssertionError.class);
    }

    @Test void executionConfigDigestUsesOrderedUtf8ObjectSerializationRatherThanStorageWhitespace() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String raw = "{\"z\": \"中文\", \"a\": {\"decimal\": \"300.000001\", \"enabled\": true}}";
        var execution = json.readTree(raw);
        String compact = "{\"z\":\"中文\",\"a\":{\"decimal\":\"300.000001\",\"enabled\":true}}";
        String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(compact.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        String stored = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(ProcurementOrderUiFixtureProducerIT.executionConfigObjectSha256(json, execution))
                .isEqualTo(expected).isNotEqualTo(stored);
        var changed = execution.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) changed.path("a")).put("decimal", "300.000002");
        assertThat(ProcurementOrderUiFixtureProducerIT.executionConfigObjectSha256(json, changed)).isNotEqualTo(expected);
        assertThat(json.writeValueAsString(execution)).isEqualTo(compact);
    }

    @Test void composedPolicyRetainsImmutableFormalCommandBytes(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var original=root.resolve("formal.json");var copy=root.resolve("composed.json");
        String commands="[{\"code\":\"qo_procurement_execution_common:generate_orders\",\"handlerParams\":{\"procurementOrderWriteContracts\":[]}}]";
        java.nio.file.Files.writeString(original,commands);java.nio.file.Files.writeString(copy,commands);
        ProcurementOrderUiFixtureProducerIT.requireUnchangedFormalCommands(original,copy);
        java.nio.file.Files.writeString(copy,commands.replace("[]","[{}]"));
        org.assertj.core.api.Assertions.assertThatThrownBy(()->ProcurementOrderUiFixtureProducerIT.requireUnchangedFormalCommands(original,copy)).isInstanceOf(AssertionError.class);
        assertThat(java.nio.file.Files.readString(original)).isEqualTo(commands);
    }
    @Test void preparationMakerReadsCategoryMembershipWithoutExpandingBrowserActor() {
        var expected=new HashSet<>(ProcurementSourceUiFixtureProducerIT.MAKER_PERMISSIONS);
        assertThat(expected).contains("model.qo_procurement_category_supplier_common.read").hasSize(20);
        assertThat(ProcurementOrderUiFixtureProducerIT.preparationPermissionCodes()).hasSize(20).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(expected).noneMatch(code->code.equals("*") || code.endsWith(".write") || code.contains("admin"));
    }
    @Test void publicPreparationRequiresActualTextualSuccessAndCompletedPhase() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var body=json.readTree("{\"code\":\"0\",\"data\":{\"phaseReached\":\"completed\",\"data\":{}}}");
        ProcurementOrderUiFixtureProducerIT.requireCompletedPublicResponse(body);
        for(String code:List.of("200","0")) {
            var invalid=body.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)invalid).put("code",Integer.parseInt(code));
            org.assertj.core.api.Assertions.assertThatThrownBy(()->ProcurementOrderUiFixtureProducerIT.requireCompletedPublicResponse(invalid)).isInstanceOf(AssertionError.class);
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("data")).put("phaseReached","pending");
        org.assertj.core.api.Assertions.assertThatThrownBy(()->ProcurementOrderUiFixtureProducerIT.requireCompletedPublicResponse(body)).isInstanceOf(AssertionError.class);
    }
    @Test void browserActorAddsOnlyPageManageAndTheExistingP12ReadRequirements() {
        var expected = new HashSet<>(ProcurementPublicSourceNativeFixture.ORDER_PERMISSIONS);
        expected.addAll(List.of("qo.procurement.manage", "page.page.read", "model.pr_purchase_order.read", "model.pr_purchase_order_line.read", "model.qo_procurement_shipment_common.read",
                "model.qo_procurement_shipment_line_common.read", "model.pr_spend_category.read"));
        assertThat(ProcurementOrderUiFixtureProducerIT.uiPermissionCodes(true)).hasSize(20).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(expected).noneMatch(code -> code.equals("*") || code.endsWith(".write") || code.contains("admin"));
    }

    @Test void independentDenialActorDiffersOnlyByIntegrationPermission() {
        var allowed = new HashSet<>(ProcurementOrderUiFixtureProducerIT.uiPermissionCodes(true));
        var denied = new HashSet<>(ProcurementOrderUiFixtureProducerIT.uiPermissionCodes(false));
        allowed.removeAll(denied);
        assertThat(allowed).containsExactly("qo.procurement.integrate");
        assertThat(denied).hasSize(19).contains("qo.procurement.manage", "page.page.read");
    }

    @Test void producerDeclaresOneExplicitlySelectedMethodAndCannotInheritSourceEight() {
        assertThat(Arrays.stream(ProcurementOrderUiFixtureProducerIT.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Test.class)).map(method -> method.getName()))
                .containsExactly("producePendingFailedUnknownAndMixedSupplierExecutionPrerequisites");
        assertThat(ProcurementOrderUiFixtureProducerIT.class.getSuperclass()).isEqualTo(ProcurementPublicSourceNativeFixture.class);
        assertThat(Arrays.stream(ProcurementPublicSourceNativeFixture.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Test.class))).isEmpty();
        var flag = ProcurementOrderUiFixtureProducerIT.class.getAnnotation(EnabledIfEnvironmentVariable.class);
        assertThat(flag.named()).isEqualTo("PROCUREMENT_ORDER_UI_FIXTURE_IT");
        assertThat(flag.matches()).isEqualTo("true");
    }
    @Test void receiptSourcesRequireAllFiveExactManagedKeysAndFullCommits() {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var sources = json.createObjectNode();
        for (String key : List.of("oss", "quote", "enterprise", "crm", "plugins")) sources.put(key, "a".repeat(40));
        assertThat(ProcurementOrderUiFixtureProducerIT.canonicalSources(sources).keySet())
                .containsExactlyInAnyOrder("core", "product", "enterprise", "crm", "plugins");
        sources.put("quote", "a".repeat(39));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProcurementOrderUiFixtureProducerIT.canonicalSources(sources))
                .isInstanceOf(AssertionError.class);
        sources.put("quote", "a".repeat(40));
        sources.remove("plugins");
        sources.put("unknown", "a".repeat(40));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProcurementOrderUiFixtureProducerIT.canonicalSources(sources))
                .isInstanceOf(AssertionError.class);
    }

    @Test void explicitAsciiCredentialsCannotSilentlyBecomeFallbackOrNewlineSecrets() {
        assertThat(ProcurementOrderUiFixtureProducerIT.validSyntheticCredential("Safe!UniqueFixture23", 16)).isTrue();
        assertThat(ProcurementOrderUiFixtureProducerIT.validSyntheticCredential(null, 12)).isFalse();
        assertThat(ProcurementOrderUiFixtureProducerIT.validSyntheticCredential("short", 12)).isFalse();
        assertThat(ProcurementOrderUiFixtureProducerIT.validSyntheticCredential("Unsafe secret value", 12)).isFalse();
        assertThat(ProcurementOrderUiFixtureProducerIT.validSyntheticCredential("UnsafeFixtureSecret\n", 12)).isFalse();
    }

    @Test void nativeConsumedStagesAndBrowserUnconsumedStagesHaveDistinctExpectedStateAndProtocol() {
        assertThat(ProcurementOrderUiFixtureProducerIT.initialState("unknown", 0)).isEqualTo("unknown");
        assertThat(ProcurementOrderUiFixtureProducerIT.initialState("unknownCreation", 0)).isEqualTo("pending");
        assertThat(ProcurementOrderUiFixtureProducerIT.responseMode("unknownCreation", 0)).isEqualTo("unknown");
        assertThat(ProcurementOrderUiFixtureProducerIT.initialState("mixed", 0)).isEqualTo("succeeded");
        assertThat(ProcurementOrderUiFixtureProducerIT.initialState("mixed", 1)).isEqualTo("failed");
        assertThat(ProcurementOrderUiFixtureProducerIT.initialState("partialCreation", 1)).isEqualTo("pending");
        assertThat(ProcurementOrderUiFixtureProducerIT.responseMode("partialCreation", 1)).isEqualTo("failed");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProcurementOrderUiFixtureProducerIT.initialState("arbitrary", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void managedReadProtocolKeepsCanonicalSpecificationWithAnExplicitProviderColumnMapping() {
        assertThat(ProcurementPublicSourceNativeFixture.READ_COLUMNS).contains("specification").doesNotContain("material_spec");
        assertThat(ProcurementOrderUiFixtureProducerIT.ERP_COLUMNS).hasSize(18).contains("Read_material_spec").doesNotContain("Read_specification");
        assertThat(ProcurementOrderUiFixtureProducerIT.ERP_COLUMNS.stream().distinct()).hasSize(18);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode encryptionProof(String mode, String profile, String state) {
        var proof = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        proof.put("schemaVersion", 1);
        proof.put("kind", "procurement-native-runtime-encryption-proof-v1");
        proof.put("runtime", "procurement-web-stack-20261010");
        proof.put("configProperty", "${FIELD_ENCRYPTION_KEY:}");
        proof.put("mode", mode);
        proof.put("actualSpringProfiles", profile);
        proof.put("environmentKeyState", state);
        proof.put("runtimeMode", "development");
        return proof;
    }

    @Test void blankKeyRequiresAnExplicitDevRuntimeProofRatherThanImplicitFallback() {
        var proof = encryptionProof("dev-passthrough", "dev,local,test", "UNSET");
        assertThat(ProcurementOrderUiFixtureProducerIT.validateEncryptionMode(proof, "")).isEqualTo("dev-passthrough");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProcurementOrderUiFixtureProducerIT.validateEncryptionMode(proof, null)).isInstanceOf(AssertionError.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProcurementOrderUiFixtureProducerIT.validateEncryptionMode(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(), "")).isInstanceOf(AssertionError.class);
        proof.put("runtimeMode", "production");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProcurementOrderUiFixtureProducerIT.validateEncryptionMode(proof, "")).isInstanceOf(AssertionError.class);
        proof.put("runtimeMode", "development");
        proof.put("actualSpringProfiles", "production");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProcurementOrderUiFixtureProducerIT.validateEncryptionMode(proof, "")).isInstanceOf(AssertionError.class);
        proof.put("actualSpringProfiles", "staging");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProcurementOrderUiFixtureProducerIT.validateEncryptionMode(proof, "")).isInstanceOf(AssertionError.class);
        proof.put("actualSpringProfiles", "production,dev");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProcurementOrderUiFixtureProducerIT.validateEncryptionMode(proof, "")).isInstanceOf(AssertionError.class);
    }

    @Test void encryptedModeRequiresActualAes256AndCannotMislabelBlankOrUnprovenValues() {
        var proof = encryptionProof("encrypted", "production", "SET");
        String key = java.util.Base64.getEncoder().encodeToString(new byte[32]); // Hermetic key shape fixture, never a deployment key.
        assertThat(ProcurementOrderUiFixtureProducerIT.validateEncryptionMode(proof, key)).isEqualTo("encrypted");
        for (String invalid : List.of("", "not-base64", java.util.Base64.getEncoder().encodeToString(new byte[16]))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProcurementOrderUiFixtureProducerIT.validateEncryptionMode(proof, invalid)).isInstanceOf(AssertionError.class);
        }
        proof.put("mode", "dev-passthrough");
        proof.put("actualSpringProfiles", "dev");
        proof.put("environmentKeyState", "UNSET");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProcurementOrderUiFixtureProducerIT.validateEncryptionMode(proof, key)).isInstanceOf(AssertionError.class);
    }

}
