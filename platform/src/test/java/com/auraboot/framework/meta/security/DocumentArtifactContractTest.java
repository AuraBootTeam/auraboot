package com.auraboot.framework.meta.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DocumentArtifactContractTest {
    private Map<String,Object> definition() {
        return new HashMap<>(Map.of("code","quote.standard","version","1.0.0","dataset","quote:snapshot_v1",
                "template","standard_v1","outputs",List.of("xlsx","pdf"),"versionPolicy","immutable-artifact",
                "artifactRelation","formal_documents","publicFields",Map.of("quote",List.of("code","total"),"lines",List.of("name"))));
    }
    private DocumentArtifactContract parse(Map<String,Object> definition) {
        return DocumentArtifactContract.from(Map.of("handlerParams",Map.of("documentDefinition",definition)),"quote");
    }
    @Test void fingerprintsAreIndependentOfDeclarationOrderAndChangeWithTheOutputContract() {
        var definition=definition();var first=parse(definition);
        definition.put("outputs",List.of("pdf","xlsx"));
        definition.put("publicFields",Map.of("lines",List.of("name"),"quote",List.of("total","code")));
        assertThat(parse(definition)).isEqualTo(first);
        definition.put("template","standard_v2");
        assertThat(parse(definition).fingerprint()).isNotEqualTo(first.fingerprint());
    }
    @Test void anUnrelatedLegacyDefinitionDoesNotOptIntoTheProtectedContract() {
        assertThat(DocumentArtifactContract.from(Map.of(),"quote")).isNull();
        var definition=definition();definition.remove("publicFields");definition.remove("artifactRelation");
        assertThat(parse(definition)).isNull();
    }
    @Test void partialOrUnknownContractsFailClosed() {
        for(String missing:List.of("publicFields","artifactRelation","template","version")) {
            var definition=definition();definition.remove(missing);
            assertThatThrownBy(()->parse(definition)).as(missing).isInstanceOf(AccessDeniedException.class);
        }
        var definition=definition();definition.put("unknown",true);
        assertThatThrownBy(()->parse(definition)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void crossRootDuplicateAndUnboundedDeclarationsAreRejected() {
        var foreign=definition();foreign.put("dataset","foreign:snapshot");
        assertThatThrownBy(()->parse(foreign)).isInstanceOf(AccessDeniedException.class);
        var noRoot=definition();noRoot.put("publicFields",Map.of("lines",List.of("name")));assertThatThrownBy(()->parse(noRoot)).isInstanceOf(AccessDeniedException.class);
        var duplicate=definition();duplicate.put("publicFields",Map.of("quote",List.of("code","code")));
        assertThatThrownBy(()->parse(duplicate)).isInstanceOf(AccessDeniedException.class);
        var oversized=definition();oversized.put("publicFields",Map.of("quote",java.util.stream.IntStream.range(0,129).mapToObj(i->"field_"+i).toList()));
        assertThatThrownBy(()->parse(oversized)).isInstanceOf(AccessDeniedException.class);
    }
}
