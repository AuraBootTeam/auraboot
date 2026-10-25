package com.auraboot.framework.bi.service.impl;

/** Failure of a configured renderer; callers must reject the export rather than degrade its layout. */
public class ReportRenderException extends RuntimeException {

    public ReportRenderException(String message) {
        super(message);
    }

    public ReportRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
