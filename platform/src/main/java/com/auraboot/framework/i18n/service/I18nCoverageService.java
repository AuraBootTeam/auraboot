package com.auraboot.framework.i18n.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.i18n.dto.I18nCoverageResponse;
import com.auraboot.framework.i18n.dto.I18nCoverageResponse.LocaleCoverage;
import com.auraboot.framework.i18n.dto.I18nCoverageResponse.MissingKeyEntry;
import com.auraboot.framework.i18n.mapper.I18nResourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for computing i18n translation coverage statistics.
 *
 * <p>The base locale is always {@code zh-CN}.  All other languages are compared against
 * the set of keys present in the base locale.  Missing keys are collected and
 * deduplicated into a compact list capped at {@value #MAX_MISSING_KEYS} entries.
 *
 * @author AuraBoot
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class I18nCoverageService {

    static final String BASE_LOCALE = "zh-CN";
    private static final int MAX_MISSING_KEYS = 50;

    private final I18nResourceMapper i18nResourceMapper;

    /**
     * Compute coverage statistics for the current tenant.
     *
     * @return a populated {@link I18nCoverageResponse}
     */
    public I18nCoverageResponse computeCoverage() {
        Long tenantId = MetaContext.getCurrentTenantId();

        // 1. Total keys in base locale
        List<Map<String, Object>> countByLangRows = i18nResourceMapper.countByLang(tenantId);
        long totalKeys = countByLangRows.stream()
            .filter(row -> BASE_LOCALE.equals(row.get("lang")))
            .mapToLong(row -> ((Number) row.get("count")).longValue())
            .findFirst()
            .orElse(0L);

        // 2. All distinct locales
        List<String> allLocales = i18nResourceMapper.selectDistinctLangs(tenantId);

        // 3. Build per-locale coverage
        Map<String, Long> countByLang = new HashMap<>();
        for (Map<String, Object> row : countByLangRows) {
            countByLang.put((String) row.get("lang"), ((Number) row.get("count")).longValue());
        }

        List<LocaleCoverage> localeCoverages = new ArrayList<>();
        for (String locale : allLocales) {
            long translated;
            long missing;
            double coverage;
            if (BASE_LOCALE.equals(locale)) {
                translated = totalKeys;
                missing = 0;
                coverage = totalKeys > 0 ? 100.0 : 0.0;
            } else {
                missing = totalKeys > 0
                    ? i18nResourceMapper.countMissingKeys(tenantId, BASE_LOCALE, locale)
                    : 0L;
                translated = totalKeys - missing;
                coverage = totalKeys > 0
                    ? Math.round((translated * 1000.0 / totalKeys)) / 10.0
                    : 0.0;
            }
            localeCoverages.add(LocaleCoverage.builder()
                .locale(locale)
                .translated(translated)
                .missing(missing)
                .coverage(coverage)
                .build());
        }

        // Sort: base locale first, then by coverage desc
        localeCoverages.sort((a, b) -> {
            if (BASE_LOCALE.equals(a.getLocale())) return -1;
            if (BASE_LOCALE.equals(b.getLocale())) return 1;
            return Double.compare(b.getCoverage(), a.getCoverage());
        });

        // 4. Collect missing key samples (capped at MAX_MISSING_KEYS across all non-base locales)
        List<MissingKeyEntry> missingKeyEntries = buildMissingKeyEntries(tenantId, allLocales);

        return I18nCoverageResponse.builder()
            .baseLocale(BASE_LOCALE)
            .totalKeys(totalKeys)
            .locales(localeCoverages)
            .missingKeys(missingKeyEntries)
            .build();
    }

    /**
     * Collect up to {@value #MAX_MISSING_KEYS} distinct missing keys across all non-base locales
     * and annotate each with the list of locales where it is absent.
     */
    private List<MissingKeyEntry> buildMissingKeyEntries(Long tenantId, List<String> allLocales) {
        // key → list of locales where it is missing
        Map<String, List<String>> keyToMissingLocales = new LinkedHashMap<>();

        for (String locale : allLocales) {
            if (BASE_LOCALE.equals(locale)) continue;
            List<String> missingKeys = i18nResourceMapper.selectMissingKeys(tenantId, BASE_LOCALE, locale, MAX_MISSING_KEYS);
            for (String key : missingKeys) {
                keyToMissingLocales.computeIfAbsent(key, k -> new ArrayList<>()).add(locale);
                if (keyToMissingLocales.size() >= MAX_MISSING_KEYS) break;
            }
            if (keyToMissingLocales.size() >= MAX_MISSING_KEYS) break;
        }

        List<MissingKeyEntry> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : keyToMissingLocales.entrySet()) {
            result.add(MissingKeyEntry.builder()
                .key(entry.getKey())
                .missingIn(entry.getValue())
                .build());
        }
        return result;
    }
}
