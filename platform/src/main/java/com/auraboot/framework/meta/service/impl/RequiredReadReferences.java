package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Host-owned reference declarations, never client SQL or an alternative permission evaluator. */
final class RequiredReadReferences {
    static final String KEY = "requiredReadReferences";
    static final int MAX_REFERENCES = 4096;
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(262144).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    record Field(String field, String model, boolean many, boolean allowEmpty) { }
    record Reference(String model, String pid) { }
    record Policy(int maxCandidates, int minimumReferences, List<Field> fields) {
        Policy { fields = List.copyOf(fields); }
        List<Reference> references(Map<String,Object> record) {
            var result = new LinkedHashSet<Reference>();
            for (var field : fields) {
                Object value = record.get(field.field());
                if (value == null || value instanceof String text && text.isBlank()) {
                    if (!field.allowEmpty()) throw invalid();
                    continue;
                }
                List<?> values;
                if (field.many()) {
                    if (value instanceof String text) {
                        if (text.length() > 262144) throw invalid();
                        try { value = JSON.readValue(text, Object.class); }
                        catch (java.io.IOException malformed) { throw invalid(); }
                    }
                    if (!(value instanceof List<?> list) || list.size() > 256 || list.isEmpty() && !field.allowEmpty()) throw invalid();
                    values = list;
                } else values = List.of(value);
                var seen = new LinkedHashSet<String>();
                for (Object item : values) {
                    if (!(item instanceof String pid) || !pid(pid) || !seen.add(pid)) throw invalid();
                    result.add(new Reference(field.model(), pid));
                    if (result.size() > MAX_REFERENCES) throw invalid();
                }
            }
            if (result.size() < minimumReferences) throw invalid();
            return List.copyOf(result);
        }
    }
    static Policy policy(ModelDefinition model) {
        if (model == null || model.getExtension() == null || !model.getExtension().containsKey(KEY)) return null;
        Object raw = model.getExtension().get(KEY);
        if (!(raw instanceof Map<?,?> map) || !map.keySet().equals(Set.of("version","references","maxCandidateRecords","minimumReferences"))
                || integer(map.get("version")) != 1 || !model.isImmutable()
                || model.getSourceType() != null && !"physical".equals(model.getSourceType())
                || "view".equals(model.getModelType()) || model.getPrimaryKey() != null && !"pid".equals(model.getPrimaryKey())) throw invalid();
        int budget = integer(map.get("maxCandidateRecords"));
        int minimum = integer(map.get("minimumReferences"));
        if (budget < 1 || budget > 10000 || minimum < 1 || minimum > MAX_REFERENCES
                || !(map.get("references") instanceof List<?> refs) || refs.isEmpty() || refs.size() > 32) throw invalid();
        var fields = new ArrayList<Field>(); var names = new LinkedHashSet<String>();
        Set<String> declared = model.getFields() == null ? Set.of() : model.getFields().stream()
                .filter(field -> !field.isTransientField() && !field.isComputedReadonly())
                .map(field -> field.getCode()).collect(java.util.stream.Collectors.toSet());
        for (Object ref : refs) {
            if (!(ref instanceof Map<?,?> item) || !item.keySet().equals(Set.of("field","model","many","allowEmpty"))
                    || !(item.get("field") instanceof String field) || !identifier(field) || !declared.contains(field) || !names.add(field)
                    || !(item.get("model") instanceof String target) || !identifier(target)
                    || !(item.get("many") instanceof Boolean many) || !(item.get("allowEmpty") instanceof Boolean empty)) throw invalid();
            fields.add(new Field(field,target,many,empty));
        }
        return new Policy(budget,minimum,fields);
    }
    private static int integer(Object value) {
        if (!(value instanceof Number number)) throw invalid();
        try { return new java.math.BigDecimal(number.toString()).intValueExact(); }
        catch (ArithmeticException | NumberFormatException malformed) { throw invalid(); }
    }
    static boolean pid(String value) { return value != null && value.matches("[0-9A-HJKMNP-TV-Z]{26}"); }
    private static boolean identifier(String value) { return value.matches("[A-Za-z][A-Za-z0-9_]{0,127}"); }
    private static MetaServiceException invalid() { return new MetaServiceException("Required record read references are incomplete or unsupported"); }
}
