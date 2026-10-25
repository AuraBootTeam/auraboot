package com.auraboot.framework.agent.dto;

import java.util.List;

/** Client draft constraints, never an authorization to mutate a business record. */
public record FormFillRequest(String targetId, String modelCode, String currentDate,
                              String timeZone, List<Field> fields) {
    public record Field(String code, String label, String type, String format,
                        @com.fasterxml.jackson.annotation.JsonProperty("enum") List<Object> choices,
                        boolean locked) { }
}
