package com.auraboot.framework.bpm.dto;

import java.util.Map;

/**
 * Result payload from an external callback.
 */
public record CallbackResult(
        boolean success,
        Map<String, Object> data,
        String errorMessage
) {}
