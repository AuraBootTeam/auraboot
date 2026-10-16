package com.auraboot.framework.openplatform.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.auraboot.framework.openplatform.service.OpenApiResourceContract.*;
import static org.assertj.core.api.Assertions.*;

class OpenApiQueryContractTest {
    private Field field(String source, Type type) { return new Field(source,type,true,false,false,false,Set.of(),false,null,Map.of(),null); }
    private OpenApiQueryContract contract(Map<String,OpenApiQueryContract.Parameter> parameters) {
        return new OpenApiQueryContract("orders.summary","orders_summary",1,"a".repeat(64),Map.of("execute","orders.query"),
                Map.of("orders","orders"),parameters,Map.of("count",field("total",Type.INTEGER)),20,100,5000,2);
    }
    @Test void rejectsUnknownMissingAndWrongTypedInputsAndReservedIdentityParameters() {
        var contract=contract(Map.of("zone",new OpenApiQueryContract.Parameter(Type.STRING,true,false,null)));
        assertThat(contract.mapParameters(Map.of("zone","A"))).containsExactlyEntriesOf(Map.of("zone","A"));
        assertThatThrownBy(()->contract.mapParameters(Map.of())).hasMessageContaining("Required");
        assertThatThrownBy(()->contract.mapParameters(Map.of("zone",42))).hasMessageContaining("String");
        assertThatThrownBy(()->contract.mapParameters(Map.of("zone","A","fromSql","orders"))).hasMessageContaining("Unknown");
        assertThatThrownBy(()->contract(Map.of("tenantId",new OpenApiQueryContract.Parameter(Type.INTEGER,true,false,null))))
                .hasMessageContaining("Reserved");
    }
    @Test void projectsOnlyGrantedAliasesAndRequiresIntegralAggregateValues() {
        var contract=contract(Map.of());
        assertThat(contract.project(Map.of("total",3L,"secret","private","tenant_id",1),Set.of("count"))).isEqualTo(Map.of("count",3L));
        assertThatThrownBy(()->contract.project(Map.of("total",3.5),Set.of("count"))).hasMessageContaining("Integer");
        assertThatThrownBy(()->new OpenApiQueryPolicy(Set.of("secret")).validate(contract)).hasMessageContaining("narrow");
    }
    @Test void semanticSourceChangesAndRequiredParameterAdditionsAreIncompatible() {
        var original=contract(Map.of());
        var changed=new OpenApiQueryContract(original.code(),original.queryCode(),2,"b".repeat(64),original.scopes(),original.sourceResources(),
                original.parameters(),original.fields(),20,100,5000,2);
        assertThatThrownBy(()->changed.requireCompatibleWith(original)).hasMessageContaining("semantics");
        assertThatThrownBy(()->contract(Map.of("zone",new OpenApiQueryContract.Parameter(Type.STRING,true,false,null))).requireCompatibleWith(original))
                .hasMessageContaining("New required");
    }
}
