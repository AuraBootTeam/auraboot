package com.auraboot.framework.plugin.handover;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ResourceHandoverContractTest {
    static ObjectNode contract() throws Exception {
        return (ObjectNode)HandoverJson.JSON.readTree("""
        {"schemaVersion":"resource-handover.v2","contractId":"test-preview","revision":1,"mode":"tenant-scoped","capabilities":["preview"],
        "source":{"pluginId":"com.test.source","version":"1.0.0"},
        "targets":[{"pluginId":"com.test.field-owner","version":"1.0.0"},{"pluginId":"com.test.dict-owner","version":"1.0.0"}],
        "resources":[{"type":"field","code":"product_trace","expectedDataType":"enum","dictCode":"trace_level","targetPluginId":"com.test.field-owner"},
        {"type":"dict","code":"trace_level","targetPluginId":"com.test.dict-owner"},
        {"type":"model_field_binding","code":"product.product_trace","modelCode":"product","fieldCode":"product_trace","targetPluginId":"com.test.field-owner"}],
        "references":[{"type":"dict","code":"lot_policy","provider":{"pluginId":"com.test.inventory","version":"1.0.0"}}]}
        """);
    }
    static ResourceHandoverContracts load(ObjectNode node) throws Exception {
        return new ResourceHandoverContracts(new Resource[]{new ByteArrayResource(HandoverJson.JSON.writeValueAsBytes(node))});
    }
    @Test void acceptsOneSourceMultipleReceiversAndStablePrecisionPreservingDigest() throws Exception {
        var registry=load(contract());var c=registry.require("test-preview");
        assertThat(c.targets()).hasSize(2);assertThat(c.resources()).hasSize(3);assertThat(c.references()).hasSize(1);
        assertThat(HandoverJson.digest(HandoverJson.JSON.readTree("{\"b\":7.5000,\"a\":null}")))
                .isEqualTo(HandoverJson.digest(HandoverJson.JSON.readTree("{\"a\":null,\"b\":7.5000}")));
        assertThat(HandoverJson.digest(c)).isNotEqualTo(HandoverJson.digest(contract().put("revision",2)));
    }
    @Test void rejectsUnknownPropertiesDuplicateKeysAndDuplicateTrustedIdentity() throws Exception {
        var node=contract().put("tableName","ab_meta_field");assertThatThrownBy(()->load(node)).hasMessageContaining("tableName");
        byte[] json="{\"schemaVersion\":\"a\",\"schemaVersion\":\"b\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(()->new ResourceHandoverContracts(new Resource[]{new ByteArrayResource(json)})).hasMessageContaining("Duplicate field");
        var r=new ByteArrayResource(HandoverJson.JSON.writeValueAsBytes(contract()));
        assertThatThrownBy(()->new ResourceHandoverContracts(new Resource[]{r,r})).hasMessageContaining("Duplicate handover contract");
    }
    @Test void rejectsGlobalRetirementAndExecutionCapabilities() throws Exception {
        var global=contract();global.put("mode","globally-retired");assertThatThrownBy(()->load(global)).hasRootCauseMessage("Only tenant-scoped preview is supported");
        var executable=contract();executable.withArray("capabilities").add("execute");var finalNode=executable;
        assertThatThrownBy(()->load(finalNode)).hasRootCauseMessage("Only tenant-scoped preview is supported");
    }
    @Test void rejectsDuplicateResourcesAndBindingOrDictionaryGaps() throws Exception {
        var node=contract();node.withArray("resources").add(node.path("resources").get(0));var duplicate=node;
        assertThatThrownBy(()->load(duplicate)).hasRootCauseMessage("Invalid or duplicate resource");
        node=contract();((ObjectNode)node.path("resources").get(2)).put("code","other.product_trace");var binding=node;
        assertThatThrownBy(()->load(binding)).hasRootCauseMessage("Invalid binding identity");
        node=contract();((ObjectNode)node.path("resources").get(0)).put("dictCode","missing");var missing=node;
        assertThatThrownBy(()->load(missing)).hasRootCauseMessage("Unresolved field dictionary");
    }
    @Test void refusesRequestInjectionEvenWithLenientApplicationMapper() {
        var mapper=HandoverJson.JSON.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        assertThatThrownBy(()->mapper.readValue("{\"contractId\":\"x\",\"tenantId\":12}",HandoverPreviewService.Request.class))
                .hasMessageContaining("Unknown handover preview property");
    }
    @Test void previewCatalogDoesNotGloballyRetireLightweightBase() throws Exception {
        var node=contract();((ObjectNode)node.path("source")).put("pluginId","com.auraboot.pcba-base");load(node);
        var legacy=new com.auraboot.framework.plugin.service.impl.PluginPackageReplacements(
                org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),HandoverJson.JSON,
                org.mockito.Mockito.mock(com.auraboot.framework.plugin.mapper.PluginResourceMapper.class));
        assertThatCode(()->legacy.requireImportable("com.auraboot.pcba-base")).doesNotThrowAnyException();
    }
    @Test void inconsistentReceiverAndProviderVersionsFailContractValidation() throws Exception {
        var node=contract();((ObjectNode)node.path("references").get(0).path("provider")).put("pluginId","com.test.field-owner").put("version","2.0.0");
        assertThatThrownBy(()->load(node)).hasRootCauseMessage("Conflicting package versions");
    }
    @Test void wildcardPackageVersionIsNotAnExactVersion() throws Exception {
        var node=contract();((ObjectNode)node.path("source")).put("version","1.x");
        assertThatThrownBy(()->load(node)).hasRootCauseMessage("Exact package version is required");
    }
    @Test void emptyCoreCatalogDoesNotInventAReplacement() throws Exception {
        var registry=new ResourceHandoverContracts(new Resource[]{});
        assertThat(registry.all()).isEmpty();assertThatThrownBy(()->registry.require("pcba-base"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
