package com.auraboot.framework.openplatform.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OpenApiEndpointContractTest {
    private OpenApiEndpointContract contract(String code, String candidate, String adapter, String binding, int version,
            String fingerprint, Map<String, String> scopes, int timeout, int concurrency) {
        return new OpenApiEndpointContract(code, candidate, adapter, binding, version, fingerprint, scopes, timeout, concurrency);
    }
    private OpenApiEndpointContract good() {
        return contract("office.read", "dynamic.get", "resource.get", "office.devices", 1, "a".repeat(64), Map.of("invoke", "office.read.invoke"), 5000, 2);
    }
    @Test void explicitMethodAndTargetAreDerivedFromTheRegisteredAdapter() {
        var c = good(); assertThat(c.path()).isEqualTo("/api/open/v1/endpoints/office.read/{recordPid}");
        assertThat(c.storageCode()).isEqualTo("endpoint:office.read");
        for (var kind : OpenApiEndpointKind.values()) {
            assertThat(OpenApiEndpointKind.fromCode(kind.code())).isEqualTo(kind);
            assertThat(kind.method()).isIn("GET", "POST", "PATCH", "DELETE");
            assertThat(kind.operation()).isNotBlank();
        }
        assertThat(OpenApiEndpointKind.RESOURCE_CREATE.write()).isTrue();
        assertThat(OpenApiEndpointKind.ACTION.resource()).isFalse();
        assertThat(OpenApiEndpointKind.QUERY.resource()).isFalse();
        assertThat(OpenApiEndpointKind.RESOURCE_LIST.write()).isFalse();
        assertThatThrownBy(() -> OpenApiEndpointKind.fromCode("http.proxy")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void noUrlReflectionOrIncompleteAuthorityCanEnterAContract() {
        var c = good();
        for (String code : Arrays.asList(null,"/api/admin","x","a".repeat(92))) assertThatThrownBy(() -> contract(code,c.candidateCode(),c.adapterCode(),c.bindingCode(),1,c.sourceFingerprint(),c.scopes(),5000,2)).isInstanceOf(IllegalArgumentException.class);
        for (String candidate : Arrays.asList(null,"https://host/path","x")) assertThatThrownBy(() -> contract(c.code(),candidate,c.adapterCode(),c.bindingCode(),1,c.sourceFingerprint(),c.scopes(),5000,2)).isInstanceOf(IllegalArgumentException.class);
        for (String binding : Arrays.asList(null,"/api/private","x")) assertThatThrownBy(() -> contract(c.code(),c.candidateCode(),c.adapterCode(),binding,1,c.sourceFingerprint(),c.scopes(),5000,2)).isInstanceOf(IllegalArgumentException.class);
        for (String fingerprint : Arrays.asList(null,"unapproved")) assertThatThrownBy(() -> contract(c.code(),c.candidateCode(),c.adapterCode(),c.bindingCode(),1,fingerprint,c.scopes(),5000,2)).isInstanceOf(IllegalArgumentException.class);
        for (Map<String,String> scopes : Arrays.asList(null,Map.<String,String>of(),Map.of("execute","office.read.invoke"),Map.of("invoke","bad scope")))
            assertThatThrownBy(() -> contract(c.code(),c.candidateCode(),c.adapterCode(),c.bindingCode(),1,c.sourceFingerprint(),scopes,5000,2)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void publishedAddressCannotSilentlyChangeSourceBindingScopeOrBudget() {
        var c = good(); contract(c.code(),c.candidateCode(),c.adapterCode(),c.bindingCode(),2,c.sourceFingerprint(),c.scopes(),6000,3).requireCompatibleWith(c);
        assertThatThrownBy(() -> contract(c.code(),"dynamic.list","resource.list",c.bindingCode(),2,c.sourceFingerprint(),c.scopes(),5000,2).requireCompatibleWith(c)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract(c.code(),c.candidateCode(),c.adapterCode(),"other.devices",2,c.sourceFingerprint(),c.scopes(),5000,2).requireCompatibleWith(c)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract(c.code(),c.candidateCode(),c.adapterCode(),c.bindingCode(),2,"b".repeat(64),c.scopes(),5000,2).requireCompatibleWith(c)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract(c.code(),c.candidateCode(),c.adapterCode(),c.bindingCode(),2,c.sourceFingerprint(),Map.of("invoke","other.invoke"),5000,2).requireCompatibleWith(c)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract(c.code(),c.candidateCode(),c.adapterCode(),c.bindingCode(),2,c.sourceFingerprint(),c.scopes(),4000,2).requireCompatibleWith(c)).isInstanceOf(IllegalArgumentException.class);
        for (int timeout : List.of(0,30001)) assertThatThrownBy(() -> contract(c.code(),c.candidateCode(),c.adapterCode(),c.bindingCode(),1,c.sourceFingerprint(),c.scopes(),timeout,2)).isInstanceOf(IllegalArgumentException.class);
        for (int slots : List.of(0,33)) assertThatThrownBy(() -> contract(c.code(),c.candidateCode(),c.adapterCode(),c.bindingCode(),1,c.sourceFingerprint(),c.scopes(),5000,slots)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract(c.code(),c.candidateCode(),c.adapterCode(),c.bindingCode(),0,c.sourceFingerprint(),c.scopes(),5000,2)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new OpenApiEndpointPolicy(false).invoke()).isFalse();
    }
}
