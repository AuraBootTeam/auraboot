package com.auraboot.framework.meta.service.impl;

import java.time.Instant;
import org.springframework.stereotype.Component;

/** Trusted retention clock; callers cannot choose cleanup or expiry timestamps. */
@Component
public class DomainExportClock {
    public Instant now(){return Instant.now();}
}
