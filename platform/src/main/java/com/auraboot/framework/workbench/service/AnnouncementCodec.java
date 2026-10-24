package com.auraboot.framework.workbench.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Canonical JSON evidence uses one deterministic serializer across submission and execution. */
@Component
public class AnnouncementCodec {
    private final ObjectMapper json;
    private final ObjectWriter canonicalWriter;
    public AnnouncementCodec(ObjectMapper json) {
        this.json=json;
        canonicalWriter=json.writer().without(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }
    public String encode(Object value) {
        try { return canonicalWriter.writeValueAsString(value); }
        catch(JsonProcessingException error) { throw new IllegalArgumentException("Invalid announcement JSON",error); }
    }
    public Map<String,Object> object(String value) {
        try { return json.readValue(value,new TypeReference<>(){}); }
        catch(JsonProcessingException error) { throw new IllegalStateException("Invalid persisted announcement JSON",error); }
    }
    public String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
