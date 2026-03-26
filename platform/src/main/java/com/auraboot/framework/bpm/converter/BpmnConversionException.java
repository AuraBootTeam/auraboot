package com.auraboot.framework.bpm.converter;

/**
 * Exception thrown when BPMN conversion (JSON to XML or XML to JSON) fails.
 */
public class BpmnConversionException extends RuntimeException {

    public BpmnConversionException(String message) {
        super(message);
    }

    public BpmnConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
