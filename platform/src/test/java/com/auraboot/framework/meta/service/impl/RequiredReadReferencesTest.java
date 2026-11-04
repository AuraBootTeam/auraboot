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
    static ModelDefinition mutableDocument() {
        var result=model(10);result.setImmutable(false);result.setModelCategory("document");result.setCommandOnlyCreate(true);
        result.getFields().get(2).setImmutable(true);
        var policy=new LinkedHashMap<String,Object>();
        ((Map<?,?>)result.getExtension().get(RequiredReadReferences.KEY)).forEach((k,v)->policy.put((String)k,v));policy.put("version",2);
        result.setExtension(Map.of(RequiredReadReferences.KEY,policy,"commandWriters",Map.of("create",List.of("fixture:create"),"update",List.of("fixture:advance"),"delete",List.of())));
        return result;
    }
    @Test void explicitV2AllowsMutableStateButOnlyFixedStoredReferenceClosure() {
        var model=mutableDocument();
        assertThat(RequiredReadReferences.policy(model).references(Map.of("members",List.of(id(1))))).containsExactly(new RequiredReadReferences.Reference("fixture_member",id(1)));
        assertThat(model.isImmutable()).isFalse();
    }
    @Test void v2RejectsMutableConditionalNumericAndVirtualReferenceFields() {
        for(String variant:List.of("mutable","conditional","numeric","transient","computed","materialized","jsonb","unknown")) {
            var model=mutableDocument();var field=model.getFields().get(2);
            switch(variant) {
                case "mutable" -> field.setImmutable(false);
                case "conditional" -> {field.setImmutable(false);field.setImmutableWhen(new FieldDefinition.ImmutableWhen());}
                case "numeric" -> field.setDataType("long");
                case "unknown" -> field.setDataType(null);
                case "jsonb" -> {field.setJsonbColumn("extension");field.setJsonbPath("members");}
                default -> field.setVirtualType(variant.equals("computed")?"computed_readonly":variant);
            }
            assertThatThrownBy(()->RequiredReadReferences.policy(model)).as(variant).isInstanceOf(MetaServiceException.class);
        }
    }
    @Test void v2RejectsUncontrolledCreationDeletionAndNonDocumentOwners() {
        for(String variant:List.of("create","delete","generic","category")) {
            var model=mutableDocument();var ext=new LinkedHashMap<>(model.getExtension());
            if(variant.equals("generic"))model.setCommandOnlyCreate(false);
            else if(variant.equals("category"))model.setModelCategory("master");
            else ext.put("commandWriters",Map.of("create",variant.equals("create")?List.of():List.of("fixture:create"),"update",List.of(),"delete",variant.equals("delete")?List.of("fixture:delete"):List.of()));
            model.setExtension(ext);assertThatThrownBy(()->RequiredReadReferences.policy(model)).as(variant).isInstanceOf(MetaServiceException.class);
        }
    }
    @Test void v2RejectsWritableAliasesOfReferenceStorageAndRetainsMissingRecordDenial() {
        for(boolean jsonAlias:List.of(false,true)) {
            var model=mutableDocument();var fields=new java.util.ArrayList<>(model.getFields());
            var alias=FieldDefinition.builder().code("alias").columnName(jsonAlias?"alias":"members").dataType("string").build();
            if(jsonAlias){alias.setJsonbColumn("members");alias.setJsonbPath("nested");}
            fields.add(alias);model.setFields(fields);
            assertThatThrownBy(()->RequiredReadReferences.policy(model)).isInstanceOf(MetaServiceException.class);
        }
        var policy=RequiredReadReferences.policy(mutableDocument());
        assertThatThrownBy(()->policy.references(Map.of())).isInstanceOf(MetaServiceException.class);
        var hidden=new java.util.HashMap<String,Object>();hidden.put("members",null);
        assertThatThrownBy(()->policy.references(hidden)).isInstanceOf(MetaServiceException.class);
    }
    @Test void v2ScalarPidEdgesSupportStringAndReferenceButNeverAlternateSourcesOrPrimaryKeys() {
        for(String kind:List.of("string","reference")) {
            var model=mutableDocument();model.getFields().get(2).setDataType(kind);
            var ext=new LinkedHashMap<>(model.getExtension());var declaration=new LinkedHashMap<String,Object>();
            ((Map<?,?>)ext.get(RequiredReadReferences.KEY)).forEach((k,v)->declaration.put((String)k,v));
            declaration.put("references",List.of(Map.of("field","members","model","fixture_member","many",false,"allowEmpty",false)));
            ext.put(RequiredReadReferences.KEY,declaration);model.setExtension(ext);
            assertThat(RequiredReadReferences.policy(model).references(Map.of("members",id(1)))).hasSize(1);
        }
        for(String variant:List.of("source","key","view")) {
            var model=mutableDocument();
            switch(variant){case "source" -> model.setSourceType("remote");case "key" -> model.setPrimaryKey("external_key");default -> model.setModelType("view");}
            assertThatThrownBy(()->RequiredReadReferences.policy(model)).isInstanceOf(MetaServiceException.class);
        }
    }
}
