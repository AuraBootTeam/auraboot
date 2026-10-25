package com.auraboot.framework.i18n.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.i18n.dto.AiTranslateRequest;
import com.auraboot.framework.i18n.dto.AiTranslationResult;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.mapper.I18nResourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service that generates DRAFT translations for missing i18n keys using the
 * configured LLM provider.  When no LLM is available the service falls back to
 * using the en-US value as a placeholder so reviewers at least know what needs
 * to be translated.
 *
 * <p>Processing rules:
 * <ul>
 *   <li>Keys that already have any translation entry (any status) in the target
 *       locale are skipped and counted as {@code skipped}.</li>
 *   <li>LLM batches that fail to parse are silently skipped and their keys
 *       counted as {@code errors}; processing continues for the remaining
 *       batches.</li>
 *   <li>All generated entries are written with {@code status=DRAFT} and
 *       {@code source=ai}.</li>
 * </ul>
 *
 * @author AuraBoot
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTranslationService {

    private static final int BATCH_SIZE = 10;
    private static final int MAX_KEYS_LIMIT = 200;

    private final I18nResourceMapper i18nResourceMapper;
    @org.springframework.lang.Nullable
    private final TranslationGenerator translationGenerator;

    /**
     * Run an AI-assisted translation job for the current tenant.
     *
     * @param request configuration (targetLocale, sourceLocale, maxKeys)
     * @return summary of generated / skipped / error counts
     */
    @Transactional(rollbackFor = Exception.class)
    public AiTranslationResult translate(AiTranslateRequest request) {
        if (translationGenerator == null) {
            throw new com.auraboot.framework.exception.BusinessException(
                    com.auraboot.framework.common.constant.ResponseCode.UnsupportedFeature,
                    "AI translation is not installed");
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        String targetLocale = request.getTargetLocale();
        String sourceLocale = request.getSourceLocale() != null ? request.getSourceLocale() : "zh-CN";
        int maxKeys = Math.min(Math.max(request.getMaxKeys(), 1), MAX_KEYS_LIMIT);

        log.info("AI translation job started: tenant={} target={} source={} maxKeys={}",
                tenantId, targetLocale, sourceLocale, maxKeys);

        // 1. Collect missing keys (keys in sourceLocale but absent in targetLocale)
        List<String> missingKeys = i18nResourceMapper.selectMissingKeys(
                tenantId, sourceLocale, targetLocale, maxKeys);

        if (missingKeys.isEmpty()) {
            log.info("No missing keys found for target={}", targetLocale);
            return AiTranslationResult.builder()
                    .generated(0).skipped(0).errors(0)
                    .targetLocale(targetLocale).sourceLocale(sourceLocale)
                    .llmUsed(false)
                    .build();
        }

        // 2. Load source values for all missing keys in one query
        Map<String, String> sourceValues = loadSourceValues(tenantId, sourceLocale, missingKeys);

        // 3. Determine which keys actually have source values
        List<String> translatableKeys = new ArrayList<>();
        for (String key : missingKeys) {
            if (sourceValues.containsKey(key)) {
                translatableKeys.add(key);
            }
        }
        int skipped = missingKeys.size() - translatableKeys.size();

        // 4. Decide strategy: LLM or en-US fallback
        boolean useLlm = translationGenerator.isAvailable(tenantId);
        log.info("Translation strategy: llm={}, translatableKeys={}", useLlm, translatableKeys.size());

        // 5. Process in batches
        int generated = 0;
        int errors = 0;

        List<List<String>> batches = partition(translatableKeys, BATCH_SIZE);
        for (List<String> batch : batches) {
            Map<String, String> translations;
            if (useLlm) {
                try {
                    translations = translationGenerator.generate(tenantId, batch, sourceValues, targetLocale);
                } catch (Exception e) {
                    log.warn("LLM batch failed for {} keys, counting as errors: {}", batch.size(), e.getMessage());
                    errors += batch.size();
                    continue;
                }
            } else {
                // Fallback: use source locale value as placeholder draft
                translations = buildFallbackTranslations(batch, sourceValues);
            }

            // 6. Save DRAFT entries (skip on conflict — existing entry wins)
            List<I18nResource> toInsert = buildResources(tenantId, translations, targetLocale);
            if (!toInsert.isEmpty()) {
                int inserted = i18nResourceMapper.batchInsertIgnore(toInsert);
                generated += inserted;
                // Keys that conflicted (already existed after all) go to skipped
                skipped += (toInsert.size() - inserted);
            }
        }

        log.info("AI translation job completed: generated={} skipped={} errors={} llmUsed={}",
                generated, skipped, errors, useLlm);

        return AiTranslationResult.builder()
                .generated(generated)
                .skipped(skipped)
                .errors(errors)
                .targetLocale(targetLocale)
                .sourceLocale(sourceLocale)
                .llmUsed(useLlm)
                .build();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Map<String, String> loadSourceValues(Long tenantId, String sourceLang, List<String> keys) {
        Map<String, String> result = new HashMap<>();
        // Use selectMissingKeys-aware logic: query all source entries for these keys
        // We iterate via selectByKeyAndLang for simplicity (keys list is ≤200)
        for (String key : keys) {
            I18nResource src = i18nResourceMapper.selectByKeyAndLang(tenantId, key, sourceLang);
            if (src != null && src.getValue() != null) {
                result.put(key, src.getValue());
            }
        }
        return result;
    }

    private Map<String, String> buildFallbackTranslations(List<String> keys, Map<String, String> sourceValues) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : keys) {
            String value = sourceValues.get(key);
            if (value != null) {
                result.put(key, value);
            }
        }
        return result;
    }

    private List<I18nResource> buildResources(Long tenantId, Map<String, String> translations, String targetLocale) {
        Long userId = MetaContext.getCurrentUserId();
        List<I18nResource> resources = new ArrayList<>();
        for (Map.Entry<String, String> entry : translations.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isBlank()) continue;
            resources.add(I18nResource.builder()
                    .pid(UniqueIdGenerator.generate())
                    .tenantId(tenantId)
                    .i18nKey(entry.getKey())
                    .lang(targetLocale)
                    .value(entry.getValue())
                    .source(I18nResource.SOURCE_AI)
                    .status(I18nResource.STATUS_DRAFT)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .createdBy(userId)
                    .deletedFlag(false)
                    .build());
        }
        return resources;
    }

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }
}
