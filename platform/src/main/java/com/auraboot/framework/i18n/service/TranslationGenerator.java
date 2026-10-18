package com.auraboot.framework.i18n.service;

import java.util.List;
import java.util.Map;

/** Tenant-scoped translation provider, independent of any model protocol. */
public interface TranslationGenerator {
    boolean isAvailable(Long tenantId);
    Map<String, String> generate(Long tenantId, List<String> keys,
                                 Map<String, String> sourceValues, String targetLocale) throws Exception;
}
