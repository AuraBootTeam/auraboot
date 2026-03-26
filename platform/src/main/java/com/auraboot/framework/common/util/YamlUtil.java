package com.auraboot.framework.common.util;

import com.auraboot.framework.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.net.URL;

public class YamlUtil {

    public static <T> T parse(URL url, Class<T> valueType) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

        T result = null;
        try {
            result = mapper.readValue(url, valueType);
        } catch (IOException e) {
            throw new BusinessException("Failed to parse YAML", e);
        }
        return result;


    }
}
