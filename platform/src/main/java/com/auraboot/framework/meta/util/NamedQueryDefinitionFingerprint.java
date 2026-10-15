package com.auraboot.framework.meta.util;

import com.auraboot.framework.meta.dto.NamedQueryDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Pins executable semantics rather than mutable titles or execution statistics. */
public final class NamedQueryDefinitionFingerprint {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private NamedQueryDefinitionFingerprint() { }
    public static String of(NamedQueryDTO query) {
        Map<String, Object> value = new TreeMap<>();
        value.put("code", query.getCode()); value.put("version", query.getCurrentVersion());
        value.put("status", query.getStatus()); value.put("resource", query.getResourceCode()); value.put("action", query.getActionCode());
        value.put("from", query.getFromSql()); value.put("where", query.getBaseWhere()); value.put("order", query.getDefaultOrder());
        value.put("policy", query.getPolicy()); value.put("connector", query.getConnectorPid());
        value.put("endpoint", query.getConnectorEndpointCode());
        value.put("fields", query.getFields().stream().map(field -> {
            Map<String, Object> item = new TreeMap<>(); item.put("code", field.getFieldCode());
            item.put("expression", field.getColumnExpr()); item.put("type", field.getDataType());
            item.put("operators", field.getOperators()); item.put("sortable", field.getSortable()); item.put("searchable", field.getSearchable());
            return item;
        }).toList());
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8))); }
        // CATCH: source pin serialization must fail closed, never omit a semantic property.
        catch (com.fasterxml.jackson.core.JsonProcessingException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Named query definition cannot be fingerprinted", failure);
        }
    }
}
