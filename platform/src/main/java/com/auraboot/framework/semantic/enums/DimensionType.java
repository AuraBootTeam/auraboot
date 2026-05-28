package com.auraboot.framework.semantic.enums;

public enum DimensionType {
    TIME,
    CATEGORICAL,
    NUMERIC,
    BOOLEAN;

    public String yamlValue() {
        return name().toLowerCase();
    }

    public static DimensionType fromYaml(String s) {
        return DimensionType.valueOf(s.toUpperCase());
    }
}
