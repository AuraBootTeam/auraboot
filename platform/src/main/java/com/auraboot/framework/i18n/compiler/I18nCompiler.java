package com.auraboot.framework.i18n.compiler;

import com.auraboot.framework.common.util.PathSafetyUtils;
import com.auraboot.framework.i18n.service.I18nResourceService;
import com.auraboot.framework.i18n.service.I18nService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;

/**
 * I18n Compiler - Compiles i18n resources from database to JSON files
 *
 * Features:
 * - Compile all languages or specific language
 * - Output nested JSON structure
 * - Write to configurable directory
 * - Clear cache after compilation
 *
 * @author AuraBoot
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class I18nCompiler {

    private final I18nResourceService i18nResourceService;
    private final I18nService i18nService;
    private final ObjectMapper objectMapper;

    @Value("${i18n.compile.output-dir:#{null}}")
    private String outputDir;

    @Value("${i18n.compile.pretty-print:true}")
    private boolean prettyPrint;

    /**
     * Compile all languages
     *
     * @return Compilation result with statistics
     */
    public CompileResult compileAll() {
        log.info("Starting i18n compilation for all languages...");
        Instant startTime = Instant.now();

        List<String> langs = i18nResourceService.getDistinctLangs();
        if (langs.isEmpty()) {
            // Default languages if none in database
            langs = Arrays.asList("zh-CN", "en-US");
        }

        CompileResult result = new CompileResult();
        result.setStartTime(startTime);

        for (String lang : langs) {
            try {
                CompileResult.LangResult langResult = compileLang(lang);
                result.getLangResults().put(lang, langResult);
                result.setTotalKeys(result.getTotalKeys() + langResult.getKeyCount());
            } catch (Exception e) {
                log.error("Failed to compile language: {}", lang, e);
                CompileResult.LangResult errorResult = new CompileResult.LangResult();
                errorResult.setSuccess(false);
                errorResult.setError(e.getMessage());
                result.getLangResults().put(lang, errorResult);
            }
        }

        result.setEndTime(Instant.now());
        result.setSuccess(result.getLangResults().values().stream().allMatch(CompileResult.LangResult::isSuccess));

        // Clear cache after compilation
        i18nService.clearCache(null);

        log.info("I18n compilation completed. Languages: {}, Total keys: {}, Duration: {}ms",
            langs.size(), result.getTotalKeys(),
            result.getEndTime().toEpochMilli() - result.getStartTime().toEpochMilli());

        return result;
    }

    /**
     * Compile a specific language
     *
     * @param lang Language code (e.g., zh-CN, en-US)
     * @return Language compilation result
     */
    public CompileResult.LangResult compileLang(String lang) {
        log.debug("Compiling language: {}", lang);

        CompileResult.LangResult result = new CompileResult.LangResult();
        result.setLang(lang);
        result.setStartTime(Instant.now());

        try {
            // Get nested resource map from service
            Map<String, Object> nestedMap = i18nResourceService.getNestedResourceMapByLang(lang);
            result.setKeyCount(countKeys(nestedMap));

            // Write to file if output directory is configured
            if (outputDir != null && !outputDir.isEmpty()) {
                String filePath = writeToFile(lang, nestedMap);
                result.setOutputPath(filePath);
            }

            // Store in memory (flat map for quick lookup)
            Map<String, String> flatMap = i18nResourceService.getResourceMapByLang(lang);
            result.setFlatMap(flatMap);

            result.setSuccess(true);
            result.setEndTime(Instant.now());

            log.debug("Compiled language: {}, keys: {}", lang, result.getKeyCount());

        } catch (Exception e) {
            log.error("Failed to compile language: {}", lang, e);
            result.setSuccess(false);
            result.setError(e.getMessage());
            result.setEndTime(Instant.now());
        }

        return result;
    }

    /**
     * Write compiled JSON to file
     *
     * @param lang Language code
     * @param data Nested map data
     * @return Output file path
     */
    private String writeToFile(String lang, Map<String, Object> data) throws IOException {
        Path dirPath = PathSafetyUtils.normalizeAbsolute(Paths.get(outputDir), "i18n outputDir");
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath);
        }

        String fileName = "i18n." + lang + ".json";
        Path filePath = PathSafetyUtils.requireSafeChild(dirPath, fileName, "i18n output file");

        ObjectMapper mapper = objectMapper.copy();
        if (prettyPrint) {
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
        }

        mapper.writeValue(filePath.toFile(), data);

        log.info("Written i18n file: {}", filePath);
        return filePath.toString();
    }

    /**
     * Count total keys in nested map
     */
    @SuppressWarnings("unchecked")
    private int countKeys(Map<String, Object> map) {
        int count = 0;
        for (Object value : map.values()) {
            if (value instanceof Map) {
                count += countKeys((Map<String, Object>) value);
            } else {
                count++;
            }
        }
        return count;
    }

    /**
     * Get compiled JSON content for a language (without writing to file)
     *
     * @param lang Language code
     * @return JSON string
     */
    public String getCompiledJson(String lang) throws IOException {
        Map<String, Object> nestedMap = i18nResourceService.getNestedResourceMapByLang(lang);

        ObjectMapper mapper = objectMapper.copy();
        if (prettyPrint) {
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
        }

        return mapper.writeValueAsString(nestedMap);
    }

    /**
     * Compilation result container
     */
    @lombok.Data
    public static class CompileResult {
        private boolean success;
        private Instant startTime;
        private Instant endTime;
        private int totalKeys;
        private Map<String, LangResult> langResults = new LinkedHashMap<>();

        @lombok.Data
        public static class LangResult {
            private String lang;
            private boolean success;
            private Instant startTime;
            private Instant endTime;
            private int keyCount;
            private String outputPath;
            private String error;
            private Map<String, String> flatMap;
        }
    }
}
