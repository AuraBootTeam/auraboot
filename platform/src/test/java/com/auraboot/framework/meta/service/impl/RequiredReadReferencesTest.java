package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RequiredReadReferencesTest {
    static String id(int n) { return "01M5Z215Q89MM18DCZ" + String.format("%08d", n); }
    static ModelDefinition model(int budget) {
        return ModelDefinition.builder().code("fixture_bundle").tableName("mt_fixture_bundle").immutable(true)
                .fields(List.of(FieldDefinition.builder().code("pid").columnName("pid").dataType("string").build(),
                        FieldDefinition.builder().code("tenant_id").columnName("tenant_id").dataType("long").build(),
                        FieldDefinition.builder().code("members").columnName("members").dataType("json").build()))
                .extension(Map.of(RequiredReadReferences.KEY, Map.of("version",1,"maxCandidateRecords",budget,"minimumReferences",1,
                        "references",List.of(Map.of("field","members","model","fixture_member","many",true,"allowEmpty",false))))).build();
    }
    @Test void parsesExactArrayOrJsonAndReturnsImmutableReferences() {
        var policy=RequiredReadReferences.policy(model(10));
        var refs=policy.references(Map.of("members",List.of(id(1),id(2))));
        assertThat(refs).containsExactly(new RequiredReadReferences.Reference("fixture_member",id(1)),new RequiredReadReferences.Reference("fixture_member",id(2)));
        assertThat(policy.references(Map.of("members","[\""+id(1)+"\",\""+id(2)+"\"]"))).isEqualTo(refs);
        assertThatThrownBy(()->refs.clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void rejectsMissingEmptyDuplicateMalformedAndOversizedReferenceSets() {
        var policy=RequiredReadReferences.policy(model(10));
        for(Object value:List.of(List.of(),List.of(id(1),id(1)),"[]","null","[17]","[] []",id(1),List.of("x' OR 1=1"),java.util.Collections.nCopies(257,id(1))))
            assertThatThrownBy(()->policy.references(Map.of("members",value))).isInstanceOf(MetaServiceException.class);
        assertThatThrownBy(()->policy.references(Map.of())).isInstanceOf(MetaServiceException.class);
    }
    @Test void configurationIsStrictAndRequiresImmutablePhysicalRecords() {
        for(int budget:List.of(0,10001)) assertThatThrownBy(()->RequiredReadReferences.policy(model(budget))).isInstanceOf(MetaServiceException.class);
        var mutable=model(10);mutable.setImmutable(false);
        assertThatThrownBy(()->RequiredReadReferences.policy(mutable)).isInstanceOf(MetaServiceException.class);
        var virtual=model(10);virtual.setSourceType("remote");
        assertThatThrownBy(()->RequiredReadReferences.policy(virtual)).isInstanceOf(MetaServiceException.class);
        var bad=model(10); var declaration=new LinkedHashMap<String,Object>();
        ((Map<?,?>)bad.getExtension().get(RequiredReadReferences.KEY)).forEach((key,value)->declaration.put((String)key,value));declaration.put("skip",true);
        bad.setExtension(Map.of(RequiredReadReferences.KEY,declaration));
        assertThatThrownBy(()->RequiredReadReferences.policy(bad)).isInstanceOf(MetaServiceException.class);
        assertThat(RequiredReadReferences.policy(ModelDefinition.builder().code("ordinary").build())).isNull();
    }
    @Test void fieldOptionalityAndExplicitGlobalMinimumAreIndependent() {
        var model=model(10);var declaration=new LinkedHashMap<String,Object>();
        ((Map<?,?>)model.getExtension().get(RequiredReadReferences.KEY)).forEach((key,value)->declaration.put((String)key,value));
        var fields=new java.util.ArrayList<>(model.getFields());fields.add(FieldDefinition.builder().code("other").columnName("other").dataType("reference").build());model.setFields(fields);
        declaration.put("references",List.of(Map.of("field","members","model","fixture_member","many",true,"allowEmpty",true),
                Map.of("field","other","model","fixture_member","many",false,"allowEmpty",true)));
        model.setExtension(Map.of(RequiredReadReferences.KEY,declaration));
        var policy=RequiredReadReferences.policy(model);
        assertThat(policy.references(Map.of("members",List.of(id(1))))).hasSize(1);
        assertThat(policy.references(Map.of("members",List.of(),"other",id(2)))).containsExactly(new RequiredReadReferences.Reference("fixture_member",id(2)));
        assertThatThrownBy(()->policy.references(Map.of("members",List.of()))).isInstanceOf(MetaServiceException.class);
        declaration.put("minimumReferences",0);
        assertThatThrownBy(()->RequiredReadReferences.policy(model)).isInstanceOf(MetaServiceException.class);
    }
}
