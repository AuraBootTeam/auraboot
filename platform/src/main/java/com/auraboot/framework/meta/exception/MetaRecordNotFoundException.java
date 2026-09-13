package com.auraboot.framework.meta.exception;

/** A record is absent from the current model and tenant scope. */
public class MetaRecordNotFoundException extends MetaServiceException {
    public MetaRecordNotFoundException(String modelCode, String recordId) {
        super("Record not found: " + recordId + " in model: " + modelCode);
    }
}
