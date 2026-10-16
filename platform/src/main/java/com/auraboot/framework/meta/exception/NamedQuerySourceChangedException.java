package com.auraboot.framework.meta.exception;

/** A trusted execution source pin differs from the current executable query definition. */
public class NamedQuerySourceChangedException extends org.springframework.security.access.AccessDeniedException {
    public NamedQuerySourceChangedException() { super("Published named query source changed"); }
}
