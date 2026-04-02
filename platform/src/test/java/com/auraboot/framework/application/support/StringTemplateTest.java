package com.auraboot.framework.application.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class StringTemplateTest {
    @Test
   public void  testJson() throws JsonProcessingException {
        String data = """
				{
				       "permalink": "/forms/1",
				       "i18n_key": "view_the_form",
				       "seq": 0
				}
				""";
        System.out.println(data);
        System.out.println(data.strip());
        System.out.println(data.stripIndent());
        System.out.println(data.stripTrailing());

        Map<String, Object> hashMap = new ObjectMapper().readValue(data, new TypeReference<>() {});

        Assertions.assertNotNull(hashMap);

        String json = new ObjectMapper().writeValueAsString(hashMap);
        Assertions.assertNotNull(json);
        System.out.println(json);

    }
}
