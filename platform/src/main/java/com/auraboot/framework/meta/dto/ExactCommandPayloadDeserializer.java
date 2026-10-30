package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Keep original JSON decimal tokens exact before command handlers receive an untyped map. */
public final class ExactCommandPayloadDeserializer extends StdDeserializer<Map<String,Object>> {
    public ExactCommandPayloadDeserializer() {super(Map.class);}
    @Override public Map<String,Object> deserialize(JsonParser parser,DeserializationContext context) throws IOException {
        if(!(parser.getCodec() instanceof ObjectMapper mapper)) throw new IOException("Command payload requires a native ObjectMapper codec");
        return mapper.readerFor(new TypeReference<LinkedHashMap<String,Object>>() {})
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(parser);
    }
}
