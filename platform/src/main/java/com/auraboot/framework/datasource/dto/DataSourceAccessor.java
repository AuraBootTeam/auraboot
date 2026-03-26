package com.auraboot.framework.datasource.dto;

import lombok.Data;

import java.util.Map;

@Data
public class DataSourceAccessor {

    private  String source;
    private String  name;

    private Map<String, Object> props;
}
