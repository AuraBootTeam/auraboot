package com.auraboot.framework.openplatform.service;

import java.util.List;

/** Modules and plugins explicitly contribute stable public capabilities through this SPI. */
public interface OpenApiCapabilityContributor {
    List<OpenApiCapabilityRegistry.Capability> capabilities();
}
