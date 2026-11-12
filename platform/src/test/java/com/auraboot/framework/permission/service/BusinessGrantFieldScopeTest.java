package com.auraboot.framework.permission.service;

import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.permission.engine.model.BusinessGrantDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class BusinessGrantFieldScopeTest {
    private final ObjectMapper json=new ObjectMapper();

    @Test void sqlNullAloneRetainsLegacySemantics() {
        assertThat(BusinessGrantFieldScope.parse(json,null,"receipt").unrestricted()).isTrue();
        var fields=BusinessGrantFieldScope.parse(json,"{\"modelCode\":\"receipt\",\"readFields\":[],\"writeFields\":[]}","receipt");
        assertThat(fields.unrestricted()).isFalse();assertThat(fields.writable()).isEmpty();
        assertThatThrownBy(()->fields.writable().add("cost")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void oneDutyKeepsIndependentFieldRulesForEachBusinessObject() {
        String scope = """
                {"version":2,"models":{
                  "task":{"readFields":["plan"],"writeFields":["plan"]},
                  "line":{"readFields":["status"],"writeFields":[]},
                  "legacy":{"unrestricted":true}
                }}
                """;
        assertThat(BusinessGrantFieldScope.parse(json, scope, "task").writable()).containsExactly("plan");
        var line = BusinessGrantFieldScope.parse(json, scope, "line");
        assertThat(line.unrestricted()).isFalse();
        assertThat(line.readable()).containsExactly("status");
        assertThat(line.writable()).isEmpty();
        assertThat(BusinessGrantFieldScope.parse(json, scope, "legacy").unrestricted()).isTrue();
        assertThatThrownBy(() -> BusinessGrantFieldScope.parse(json, scope, "unconfigured"))
                .isInstanceOf(MetaServiceException.class);
    }

    @ParameterizedTest
    @ValueSource(strings={
            "{\"version\":3,\"models\":{\"receipt\":{\"unrestricted\":true}}}",
            "{\"version\":2.0,\"models\":{\"receipt\":{\"unrestricted\":true}}}",
            "{\"version\":2,\"models\":{}}",
            "{\"version\":2,\"models\":{\"receipt\":{\"unrestricted\":false}}}",
            "{\"version\":2,\"models\":{\"receipt\":{\"unrestricted\":true,\"readFields\":[]}}}",
            "{\"version\":2,\"models\":{\"receipt\":{\"unrestricted\":true},\"other\":{\"readFields\":[],\"writeFields\":[\"cost\",\"cost\"]}}}",
            "{\"version\":2,\"models\":{\"receipt\":{\"unrestricted\":true},\"receipt\":{\"readFields\":[],\"writeFields\":[]}}}"
    })
    void badVersionOrAnotherModelsInvalidConfigurationCannotHide(String raw) {
        assertThatThrownBy(() -> BusinessGrantFieldScope.parse(json, raw, "receipt")).isInstanceOf(MetaServiceException.class);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={51,128})
    void oneDomainCanRetainItsObjectsWhileNarrowingOnlyOne(int count) {
        var models = new java.util.TreeMap<String,BusinessGrantDecision.Fields>();
        for (int index=0;index<count;index++) models.put("domain_object_"+index,
                index==0 ? new BusinessGrantDecision.Fields(false,java.util.Set.of("remark"),java.util.Set.of())
                        : BusinessGrantDecision.Fields.legacy());
        String encoded = BusinessGrantFieldScope.encode(json,models,true);
        assertThat(BusinessGrantFieldScope.decode(json,encoded).models()).hasSize(count);
        assertThat(BusinessGrantFieldScope.parse(json,encoded,"domain_object_0").readable()).containsExactly("remark");
        assertThat(BusinessGrantFieldScope.parse(json,encoded,"domain_object_1").unrestricted()).isTrue();
        assertThat(BusinessGrantFieldScope.select(json,encoded,"future_object")).isEmpty();
    }

    @Test void domainCapacityStillRejectsMoreThan128ObjectsOnReadAndWrite() throws Exception {
        var models = new java.util.TreeMap<String,BusinessGrantDecision.Fields>();
        var raw = new java.util.TreeMap<String,Object>();
        for(int index=0;index<129;index++) {
            models.put("domain_object_"+index,BusinessGrantDecision.Fields.legacy());
            raw.put("domain_object_"+index,java.util.Map.of("unrestricted",true));
        }
        assertThatThrownBy(() -> BusinessGrantFieldScope.encode(json,models,true)).isInstanceOf(MetaServiceException.class);
        String encoded=json.writeValueAsString(java.util.Map.of("version",2,"models",raw));
        assertThatThrownBy(() -> BusinessGrantFieldScope.decode(json,encoded)).isInstanceOf(MetaServiceException.class);
    }

    @Test void omittedModelIsDifferentFromAnExplicitEmptyFieldList() {
        String scope = "{\"version\":2,\"models\":{\"receipt\":{\"readFields\":[],\"writeFields\":[]}}}";
        assertThat(BusinessGrantFieldScope.select(json, scope, "other")).isEmpty();
        assertThat(BusinessGrantFieldScope.select(json, scope, "receipt")).isPresent();
        assertThat(BusinessGrantFieldScope.select(json, "{\"modelCode\":\"receipt\",\"readFields\":[],\"writeFields\":[]}", "other")).isEmpty();
        assertThat(BusinessGrantFieldScope.select(json, null, "other")).get().satisfies(fields -> assertThat(fields.unrestricted()).isTrue());
    }

    @ParameterizedTest
    @ValueSource(strings={"null","[]","{}","{broken}",
            "{\"modelCode\":\"other\",\"readFields\":[],\"writeFields\":[]}",
            "{\"modelCode\":\"receipt\",\"readFields\":[],\"writeFields\":[\"cost\",\"cost\"]}",
            "{\"modelCode\":\"receipt\",\"readFields\":[],\"writeFields\":[\"*\"]}",
            "{\"modelCode\":\"receipt\",\"readFields\":[],\"writeFields\":[],\"unknown\":true}",
            "{\"modelCode\":\"receipt\",\"readFields\":[],\"writeFields\":null}"})
    void malformedOrWrongModelNeverBecomesUnrestricted(String raw) {
        assertThatThrownBy(()->BusinessGrantFieldScope.parse(json,raw,"receipt")).isInstanceOf(MetaServiceException.class);
    }
}
