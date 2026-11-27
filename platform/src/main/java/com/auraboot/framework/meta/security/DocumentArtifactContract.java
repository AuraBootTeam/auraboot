package com.auraboot.framework.meta.security;

import com.auraboot.framework.plugin.extension.BusinessResourceAuthorization;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.security.access.AccessDeniedException;

import java.util.*;

/** Finite output dependencies belong to the published document, never to a role or upload DTO. */
public record DocumentArtifactContract(String code, String version, String relation, Set<String> outputs,
        Map<String,Set<String>> fields, String fingerprint) {
    public DocumentArtifactContract {
        outputs=Set.copyOf(outputs); fields=Map.copyOf(fields);
    }

    public static DocumentArtifactContract from(Map<String,Object> config, String rootModel) {
        Object parameters=config==null?null:config.get("handlerParams");
        Object raw=parameters instanceof Map<?,?> values?values.get("documentDefinition"):null;
        if(!(raw instanceof Map<?,?> definition)) return null;
        if(!definition.containsKey("publicFields") && !definition.containsKey("artifactRelation")) return null;
        if(!definition.keySet().equals(Set.of("code","version","dataset","template","outputs","versionPolicy","publicFields","artifactRelation"))) throw denied();
        String code=text(definition.get("code")), version=text(definition.get("version"));
        String dataset=text(definition.get("dataset")), template=text(definition.get("template"));
        String relation=text(definition.get("artifactRelation"));
        BusinessResourceAuthorization.identifier(relation);
        if(rootModel==null || !dataset.startsWith(rootModel+":") || !"immutable-artifact".equals(definition.get("versionPolicy"))
                || !(definition.get("outputs") instanceof Collection<?> values) || values.isEmpty() || values.size()>8
                || !(definition.get("publicFields") instanceof Map<?,?> declarations) || declarations.isEmpty() || declarations.size()>16) throw denied();
        Set<String> outputs=new TreeSet<>();
        for(Object value:values) if(!outputs.add(text(value))) throw denied();
        Map<String,Set<String>> fields=new TreeMap<>();
        for(var entry:declarations.entrySet()) {
            String model=text(entry.getKey()); BusinessResourceAuthorization.identifier(model);
            if(!(entry.getValue() instanceof Collection<?> names) || names.isEmpty() || names.size()>128) throw denied();
            Set<String> selected=new TreeSet<>();
            for(Object name:names) {String field=text(name);BusinessResourceAuthorization.identifier(field);if(!selected.add(field))throw denied();}
            fields.put(model,Set.copyOf(selected));
        }
        if(!fields.containsKey(rootModel))throw denied();
        Map<String,Object> canonical=new TreeMap<>();
        Map<String,List<String>> orderedFields=new TreeMap<>();fields.forEach((model,names)->orderedFields.put(model,names.stream().sorted().toList()));
        canonical.putAll(Map.of("code",code,"version",version,"dataset",dataset,"template",template,"relation",relation,
                "outputs",outputs.stream().sorted().toList(),"fields",orderedFields,"versionPolicy","immutable-artifact"));
        try {
            byte[] bytes=new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(canonical);
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            return new DocumentArtifactContract(code,version,relation,outputs,fields,hash);
        } catch(com.fasterxml.jackson.core.JsonProcessingException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot fingerprint the document output contract",failure);
        }
    }

    private static String text(Object value) {
        if(!(value instanceof String text) || text.isBlank() || text.length()>256 || !text.equals(text.strip()))throw denied();
        return text;
    }
    public static AccessDeniedException denied(){return new AccessDeniedException("Document artifact source contract is unavailable");}
}
